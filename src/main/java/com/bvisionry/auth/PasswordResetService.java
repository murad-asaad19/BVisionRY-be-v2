package com.bvisionry.auth;

import com.bvisionry.auth.entity.PasswordResetToken;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The "forgot your password" flow, mirroring the invitation-token pattern:
 * a single-use, short-lived UUID token emailed as a link.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final FrontendUrls frontendUrls;

    /**
     * Issues a reset token and emails the link. Deliberately silent for an
     * unknown email — the endpoint always answers 204, so it cannot be used to
     * enumerate which addresses have accounts.
     *
     * <p>SSO-only accounts (no password hash) are included on purpose: proving
     * email ownership through this flow is exactly the "account recovery" that
     * {@link AuthService#changePassword} points them to for setting an initial
     * password.
     */
    @Transactional
    public void requestReset(String email) {
        String normalized = email.toLowerCase().trim();
        userRepository.findByEmail(normalized).ifPresentOrElse(user -> {
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
            PasswordResetToken saved = tokenRepository.save(token);

            String resetUrl = frontendUrls.path("/reset-password/" + saved.getToken());
            // Fire-and-forget on the emailExecutor pool, matching the
            // invitation flow: SMTP latency must not block the request.
            emailService.sendPasswordResetEmailAsync(normalized, resetUrl, saved.getExpiresAt());
        }, () -> log.info("Password reset requested for unknown email"));
    }

    /**
     * Sets a new password for the user behind a usable token, then spends every
     * outstanding token for that user and revokes all refresh tokens (symmetric
     * with {@link AuthService#changePassword} — any session predating the reset
     * must die).
     */
    @Transactional
    public void resetPassword(UUID token, String newPassword) {
        PasswordResetToken reset = tokenRepository.findByToken(token)
                .filter(PasswordResetToken::isUsable)
                // One message for unknown/expired/spent — no oracle for which it was.
                .orElseThrow(() -> new BadRequestException(
                        "This password reset link is invalid or has expired. Please request a new one."));

        User user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        Instant now = Instant.now();
        tokenRepository.markAllUsedForUser(user.getId(), now);
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
    }
}
