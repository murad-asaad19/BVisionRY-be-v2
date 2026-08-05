package com.bvisionry.auth;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.dto.LoginRequest;
import com.bvisionry.auth.dto.RefreshTokenRequest;
import com.bvisionry.auth.entity.RefreshToken;
import com.bvisionry.auth.entity.User;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.auth.jwt.TokenType;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.AuthenticationException;
import com.bvisionry.common.exception.SsoFlowException;
import com.bvisionry.organization.entity.Organization;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the refresh-token rotation, theft-detection, and
 * password-change revocation paths added to {@link AuthService}.
 *
 * <p>These mock the repositories and {@link JwtProvider} so each scenario
 * exercises only the AuthService logic. Integration-level coverage (DB
 * round-tripping the {@code refresh_tokens} table) lives elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtProvider jwtProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private AuthService authService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("ada@example.com");
        user.setName("Ada");
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("hash");
    }

    // ---------- refresh: rotation ----------

    @Test
    void refresh_rotatesRefreshToken_revokingOldAndPersistingNew() {
        UUID oldJti = UUID.randomUUID();
        UUID newJti = UUID.randomUUID();
        String presented = "old.refresh.jwt";
        Instant now = Instant.now();

        RefreshToken stored = activeStoredToken(oldJti, now.plusSeconds(3600));

        Claims oldClaims = claimsWithJti(oldJti);
        when(jwtProvider.parseAndValidate(presented, TokenType.REFRESH)).thenReturn(Optional.of(oldClaims));
        when(refreshTokenRepository.findByJti(oldJti)).thenReturn(Optional.of(stored));
        when(jwtProvider.generateAccessToken(user)).thenReturn("new.access.jwt");
        when(jwtProvider.generateRefreshToken(user)).thenReturn(
                new JwtProvider.IssuedRefreshToken("new.refresh.jwt", newJti, now, now.plusSeconds(86400)));

        AuthResponse response = authService.refresh(new RefreshTokenRequest(presented));

        assertThat(response.token()).isEqualTo("new.access.jwt");
        assertThat(response.refreshToken()).isEqualTo("new.refresh.jwt");

        // Old row revoked + linked to replacement.
        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(savedCaptor.capture());

        // The first save() persists the new row; the second updates the old.
        // Order is implementation-defined for the captor — assert by jti.
        RefreshToken newRow = savedCaptor.getAllValues().stream()
                .filter(rt -> newJti.equals(rt.getJti())).findFirst().orElseThrow();
        RefreshToken rotatedOld = savedCaptor.getAllValues().stream()
                .filter(rt -> oldJti.equals(rt.getJti())).findFirst().orElseThrow();

        assertThat(newRow.getUser()).isEqualTo(user);
        assertThat(newRow.getRevokedAt()).isNull();

        assertThat(rotatedOld.getRevokedAt()).isNotNull();
        assertThat(rotatedOld.getReplacedByJti()).isEqualTo(newJti);

        // No theft path triggered.
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    // ---------- refresh: theft detection ----------

    @Test
    void refresh_replayOfRevokedToken_revokesAllForUser_andThrows() {
        UUID jti = UUID.randomUUID();
        String presented = "stolen.refresh.jwt";

        RefreshToken stored = activeStoredToken(jti, Instant.now().plusSeconds(3600));
        // Already rotated in a prior call.
        stored.setRevokedAt(Instant.now().minusSeconds(60));
        stored.setReplacedByJti(UUID.randomUUID());

        Claims claims = claimsWithJti(jti);
        when(jwtProvider.parseAndValidate(presented, TokenType.REFRESH)).thenReturn(Optional.of(claims));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(presented)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("reuse detected");

        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any(Instant.class));
        // Must not mint a replacement on theft.
        verify(jwtProvider, never()).generateAccessToken(any());
        verify(jwtProvider, never()).generateRefreshToken(any());
    }

    @Test
    void refresh_typeMismatch_throws_andDoesNotTouchStore() {
        // An access token presented at /refresh: parseAndValidate returns empty because the
        // typ claim doesn't match REFRESH.
        when(jwtProvider.parseAndValidate("access.token", TokenType.REFRESH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("access.token")))
                .isInstanceOf(AuthenticationException.class);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void refresh_unknownJti_throws() {
        UUID jti = UUID.randomUUID();
        Claims claims = claimsWithJti(jti);
        when(jwtProvider.parseAndValidate("rt", TokenType.REFRESH)).thenReturn(Optional.of(claims));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("rt")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("not recognized");
    }

    @Test
    void refresh_expiredRow_throws() {
        UUID jti = UUID.randomUUID();
        RefreshToken stored = activeStoredToken(jti, Instant.now().minusSeconds(60));
        Claims claims = claimsWithJti(jti);

        when(jwtProvider.parseAndValidate("rt", TokenType.REFRESH)).thenReturn(Optional.of(claims));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("rt")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("expired");
    }

    // ---------- changePassword ----------

    @Test
    void changePassword_revokesAllRefreshTokensAfterUpdate() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old", "hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        authService.changePassword(userId, "old", "new-password-123");

        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any(Instant.class));
    }

    @Test
    void changePassword_wrongCurrent_doesNotRevokeAnything() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(userId, "wrong", "new-password-123"))
                .isInstanceOf(AuthenticationException.class);

        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
        verify(userRepository, never()).save(any());
    }

    // ---------- logout ----------

    @Test
    void logout_revokesAllForInferredUser() {
        when(jwtProvider.getUserIdFromToken("any.token")).thenReturn(userId);

        authService.logout("any.token");

        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any(Instant.class));
    }

    @Test
    void logout_blankToken_isNoOp() {
        authService.logout("");
        authService.logout(null);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void revokeAllRefreshTokensForUser_delegatesToRepository() {
        when(refreshTokenRepository.revokeAllForUser(eq(userId), any(Instant.class))).thenReturn(3);

        int revoked = authService.revokeAllRefreshTokensForUser(userId);

        assertThat(revoked).isEqualTo(3);
    }

    // ---------- resolveSsoUser ----------

    @Test
    void resolveSsoUser_autoLinksProviderOntoExistingPasswordAccount() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        User resolved = authService.resolveSsoUser("ada@example.com", "http://avatar", "GOOGLE");

        assertThat(resolved.getSsoProvider()).isEqualTo("GOOGLE");
        assertThat(resolved.getPasswordHash()).isEqualTo("hash");
    }

    @Test
    void resolveSsoUser_differentLinkedProvider_throwsWithCode() {
        user.setSsoProvider("MICROSOFT");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resolveSsoUser("ada@example.com", null, "GOOGLE"))
                .isInstanceOfSatisfying(SsoFlowException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("sso_provider_mismatch"));
    }

    // ---------- suspended organization: every mint path ----------

    /**
     * A suspended ORGANIZATION must be refused where tokens are MINTED, not only where
     * they are used. {@link com.bvisionry.auth.jwt.JwtAuthenticationFilter} and
     * {@link com.bvisionry.auth.jwt.DownloadTokenAuthenticationFilter} already refuse
     * such a principal per-request (via {@code AuthenticationEligibility}), so the
     * guards below were not exploitable — but mint and accept disagreeing is the
     * asymmetry that becomes a hole the moment a filter is relaxed or a new consumer
     * trusts a freshly minted token without re-checking. All three guards shipped
     * untested; these pin them.
     *
     * <p>Each also asserts NOTHING was minted. Asserting only the throw would stay green
     * if the guard moved below {@code issueTokens} — the token would already exist.
     */
    @Test
    void login_suspendedOrganization_isRefusedAndMintsNothing() {
        user.setOrganization(suspendedOrg());
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "pw")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("organization has been suspended");

        assertNothingWasMinted();
    }

    @Test
    void refresh_suspendedOrganization_isRefusedAndRotatesNothing() {
        user.setOrganization(suspendedOrg());
        UUID jti = UUID.randomUUID();
        Claims claims = claimsWithJti(jti);
        RefreshToken stored = activeStoredToken(jti, Instant.now().plusSeconds(3600));
        when(jwtProvider.parseAndValidate("rt", TokenType.REFRESH)).thenReturn(Optional.of(claims));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("rt")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("organization has been suspended");

        assertNothingWasMinted();
        // The presented token must also survive: refusing a suspended org is not theft,
        // so it must not revoke or rotate the row.
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    /** The Google/OAuth2 path. Enterprise SSO has its own guard, covered in SsoLoginServiceTest. */
    @Test
    void resolveSsoUser_suspendedOrganization_isRefusedWithCode() {
        user.setOrganization(suspendedOrg());
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resolveSsoUser("ada@example.com", null, "GOOGLE"))
                .isInstanceOfSatisfying(SsoFlowException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("sso_org_suspended"));

        assertNothingWasMinted();
    }

    // ---------- helpers ----------

    private static Organization suspendedOrg() {
        Organization org = new Organization();
        org.setActive(false);
        return org;
    }

    private void assertNothingWasMinted() {
        verify(jwtProvider, never()).generateAccessToken(any());
        verify(jwtProvider, never()).generateRefreshToken(any());
    }

    private RefreshToken activeStoredToken(UUID jti, Instant expiresAt) {
        RefreshToken rt = new RefreshToken();
        rt.setId(UUID.randomUUID());
        rt.setJti(jti);
        rt.setUser(user);
        rt.setIssuedAt(Instant.now().minusSeconds(60));
        rt.setExpiresAt(expiresAt);
        return rt;
    }

    private static Claims claimsWithJti(UUID jti) {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn(jti.toString());
        return claims;
    }
}
