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
import com.bvisionry.common.exception.AccountNotActiveException;
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
import static org.assertj.core.api.Assertions.catchThrowable;
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

    // ---------- updateProfile ----------

    @Test
    void updateProfile_trimsAndSavesName_returningUpdatedResponse() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var response = authService.updateProfile(userId, "  Grace Hopper  ");

        assertThat(user.getName()).isEqualTo("Grace Hopper");
        assertThat(response.name()).isEqualTo("Grace Hopper");
        verify(userRepository).save(user);
    }

    @Test
    void updateProfile_unknownUser_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.updateProfile(userId, "Anyone"))
                .isInstanceOf(AuthenticationException.class);

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
     * they are used. {@link com.bvisionry.auth.jwt.JwtAuthenticationFilter} already
     * refuses such a principal per-request (via {@code AuthenticationEligibility}), so
     * the guards below were not exploitable — but mint and accept disagreeing is the
     * asymmetry that becomes a hole the moment the filter is relaxed or a new consumer
     * trusts a freshly minted token without re-checking. All three guards shipped
     * untested; these pin them.
     *
     * <p>Each also asserts NOTHING was minted. Asserting only the throw would stay green
     * if the guard moved below {@code issueTokens} — the token would already exist.
     */
    /**
     * ENUMERATION TIMING ORACLE. A known address pays a bcrypt compare (~60-80ms by
     * design); an unknown one used to return the instant the lookup missed. That
     * latency gap answers "does this address have an account here?" — the very
     * question the single "Invalid email or password" message and the always-204
     * forgot-password endpoint exist to refuse.
     *
     * <p>Asserts the compare HAPPENS, not that it succeeds: delete the dummy compare
     * and this fails, which is the only thing a mock can honestly pin about timing.
     */
    @Test
    void login_unknownEmail_stillPaysAPasswordCompare() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "pw")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("Invalid email or password");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("pw"), hash.capture());
        assertThat(hash.getValue())
                .as("must be a real bcrypt hash, or the compare is free and the oracle survives")
                .startsWith("$2a$10$")
                .hasSize(60);
    }

    /**
     * Same hole, second door: an SSO-only account has no password hash, and
     * {@code matches(raw, null)} short-circuits for free. It must cost the same as a
     * password account, or latency separates those two populations instead.
     */
    @Test
    void login_ssoOnlyAccountWithNoPasswordHash_stillPaysAPasswordCompare() {
        user.setPasswordHash(null);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "pw")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("Invalid email or password");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("pw"), hash.capture());
        assertThat(hash.getValue()).isNotNull().startsWith("$2a$10$");
    }

    /**
     * ENUMERATION UNIFORMITY, ON THE REAL SERVICE. The two ways a password sign-in can
     * be rejected — no such address, and a wrong password for a real one — must be
     * indistinguishable, or the response itself answers "does this address have an
     * account here?" and the whole backoff/always-204 apparatus around it is decoration.
     *
     * <p>The two refusals are compared to EACH OTHER, not to a literal, so the test
     * fails whichever side of the pair someone changes — including a later refactor
     * that splits the single {@code user == null || !matched} guard into two throws and
     * gives the not-found branch its own wording. The controller-level backoff test
     * asserts a similar property against a mocked AuthService, which can only ever
     * restate its own stub; this drives the production method.
     */
    @Test
    void login_unknownEmailAndWrongPassword_areRefusedIdentically() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("pw"), any())).thenReturn(false);

        Throwable unknownEmail = catchThrowable(
                () -> authService.login(new LoginRequest("ghost@example.com", "pw")));
        Throwable wrongPassword = catchThrowable(
                () -> authService.login(new LoginRequest("ada@example.com", "pw")));

        assertThat(unknownEmail).isInstanceOf(AuthenticationException.class);
        assertThat(wrongPassword)
                .hasSameClassAs(unknownEmail)
                .hasMessage(unknownEmail.getMessage());
    }

    /**
     * The CONCRETE type is load-bearing on both refusals below: the controller
     * exempts {@link AccountNotActiveException} from the login backoff, so a
     * plain {@link AuthenticationException} here would let a CORRECT password
     * advance the guess counter and lock the legitimate owner out of their own
     * account once it (or the org) is restored.
     */
    @Test
    void login_inactiveAccount_isRefusedAsAccountNotActive_andMintsNothing() {
        user.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "pw")))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessage("Account is not active");

        assertNothingWasMinted();
    }

    @Test
    void login_suspendedOrganization_isRefusedAndMintsNothing() {
        user.setOrganization(suspendedOrg());
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "pw")))
                .isInstanceOf(AccountNotActiveException.class)
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
