package com.bvisionry.config;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Provisions the platform SUPER_ADMIN from configuration at startup, replacing the
 * hardcoded credential that V16 used to seed (removed by V84). The credential never
 * lives in source or a migration: it is read from {@code bvisionry.bootstrap.super-admin.*},
 * which is supplied via environment variables in production (and via non-secret dev
 * values in the dev profile).
 *
 * <p>The flow is idempotent — if a user with the configured email already exists it is
 * left untouched (so a rotated password or promoted role is never clobbered). If no
 * email is configured the bootstrap is a no-op, so an operator who manages the admin
 * out of band (or whose env simply isn't set) gets no auto-created account.
 */
@Component
public class SuperAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public SuperAdminBootstrap(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bvisionry.bootstrap.super-admin.email:}") String email,
            @Value("${bvisionry.bootstrap.super-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureSuperAdmin() {
        if (email == null || email.isBlank()) {
            // No bootstrap configured: refuse to invent a credential. In prod this means
            // the operator must provision the admin out of band (or set the env vars).
            log.warn("No bvisionry.bootstrap.super-admin.email configured; skipping super-admin bootstrap. "
                    + "Set SUPERADMIN_EMAIL / SUPERADMIN_PASSWORD to auto-provision one.");
            return;
        }

        String normalizedEmail = email.toLowerCase();

        // Idempotent: never overwrite an existing account (preserves a rotated password
        // or an admin that was promoted/demoted through the app).
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.info("Super-admin bootstrap: user {} already exists, leaving untouched.", normalizedEmail);
            return;
        }

        if (password == null || password.isBlank()) {
            // Fail fast: a super admin without a password would be a worse footgun than
            // the seeded credential we just removed.
            throw new IllegalStateException(
                    "bvisionry.bootstrap.super-admin.email is set but the password is empty; "
                            + "set bvisionry.bootstrap.super-admin.password (SUPERADMIN_PASSWORD) to bootstrap a super admin.");
        }

        // Mirror AuthService account defaults: an ACTIVE user, activated now. userType
        // defaults to "LEADER" on the entity; org is left null (platform-level admin).
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setName("Super Admin");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user.setActivatedAt(Instant.now());
        user.setPasswordHash(passwordEncoder.encode(password));

        try {
            userRepository.save(user);
            log.info("Super-admin bootstrap: provisioned SUPER_ADMIN {}.", normalizedEmail);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent instance that created the same row; treat the
            // already-present admin as success (idempotent), matching the create-or-return
            // pattern used elsewhere (CertificateService / SurveyResponseService).
            log.info("Super-admin bootstrap: {} created concurrently, leaving untouched.", normalizedEmail);
        }
    }
}
