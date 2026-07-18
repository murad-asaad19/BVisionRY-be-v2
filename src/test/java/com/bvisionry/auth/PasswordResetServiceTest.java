package com.bvisionry.auth;

import com.bvisionry.auth.entity.PasswordResetToken;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the forgot-password flow: token issuance (including the
 * silent unknown-email path), single-use + expiry enforcement, and the
 * password-set side effects (all tokens spent, all sessions revoked).
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private FrontendUrls frontendUrls;

    @InjectMocks
    private PasswordResetService passwordResetService;

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
        user.setPasswordHash("old-hash");
    }

    // ---------- requestReset ----------

    @Test
    void requestReset_knownEmail_savesTokenAndEmailsLink() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(frontendUrls.path(any(String.class)))
                .thenAnswer(inv -> "http://localhost:3000" + inv.getArgument(0));

        passwordResetService.requestReset("Ada@Example.com ");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getToken()).isNotNull();
        assertThat(saved.getValue().getExpiresAt())
                .isCloseTo(Instant.now().plus(PasswordResetService.TOKEN_TTL), within(java.time.Duration.ofSeconds(5)));

        verify(emailService).sendPasswordResetEmailAsync(
                eq("ada@example.com"),
                contains("/reset-password/" + saved.getValue().getToken()),
                eq(saved.getValue().getExpiresAt()));
    }

    @Test
    void requestReset_unknownEmail_isSilent() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        passwordResetService.requestReset("ghost@example.com");

        verifyNoInteractions(tokenRepository, emailService);
    }

    // ---------- resetPassword ----------

    @Test
    void resetPassword_usableToken_setsPasswordSpendsTokensAndRevokesSessions() {
        PasswordResetToken token = usableToken();
        when(tokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        passwordResetService.resetPassword(token.getToken(), "new-password-123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
        verify(tokenRepository).markAllUsedForUser(eq(userId), any(Instant.class));
        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any(Instant.class));
    }

    @Test
    void resetPassword_ssoOnlyAccount_setsInitialPassword() {
        // Email ownership is proven by the emailed token, so a passwordless SSO
        // account may set its first password here (the "account recovery" that
        // change-password refers such users to).
        user.setPasswordHash(null);
        user.setSsoProvider("google");
        PasswordResetToken token = usableToken();
        when(tokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        passwordResetService.resetPassword(token.getToken(), "new-password-123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void resetPassword_expiredToken_rejected() {
        PasswordResetToken token = usableToken();
        token.setExpiresAt(Instant.now().minusSeconds(60));
        when(tokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword(token.getToken(), "new-password-123"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid or has expired");

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verifyNoInteractions(refreshTokenRepository, passwordEncoder);
    }

    @Test
    void resetPassword_alreadyUsedToken_rejected() {
        PasswordResetToken token = usableToken();
        token.setUsedAt(Instant.now().minusSeconds(60));
        when(tokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword(token.getToken(), "new-password-123"))
                .isInstanceOf(BadRequestException.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verifyNoInteractions(refreshTokenRepository, passwordEncoder);
    }

    @Test
    void resetPassword_unknownToken_rejected() {
        UUID bogus = UUID.randomUUID();
        when(tokenRepository.findByToken(bogus)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword(bogus, "new-password-123"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(refreshTokenRepository, passwordEncoder);
    }

    private PasswordResetToken usableToken() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return token;
    }
}
