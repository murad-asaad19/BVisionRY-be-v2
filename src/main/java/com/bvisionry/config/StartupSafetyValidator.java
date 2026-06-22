package com.bvisionry.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-fast guard (B6): refuses to start the {@code prod} profile with the
 * built-in development secrets or insecure cookies.
 *
 * <p>The source default profile is {@code dev} (for local IDE convenience), so a
 * production deploy depends on {@code SPRING_PROFILES_ACTIVE=prod} actually being
 * set — and a prod boot is only safe if the secrets/cookie flags were supplied
 * too. Rather than silently run insecure, we throw during context init (which
 * aborts startup) when the prod profile is active but still carries a dev-default
 * JWT secret, dev-default encryption key, or {@code cookies.secure=false}. This
 * complements the image-level default profile in the Dockerfile.
 */
@Component
@Slf4j
public class StartupSafetyValidator implements InitializingBean {

    /** The non-secret dev defaults shipped in application.properties — never valid in prod. */
    private static final String DEV_JWT_SECRET =
            "bvisionry-dev-secret-key-change-in-production-must-be-at-least-256-bits-long";
    private static final String DEV_ENCRYPTION_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final Environment environment;
    private final boolean cookiesSecure;
    private final String jwtSecret;
    private final String encryptionKey;

    public StartupSafetyValidator(
            Environment environment,
            @Value("${bvisionry.security.cookies.secure:false}") boolean cookiesSecure,
            @Value("${bvisionry.jwt.secret:}") String jwtSecret,
            @Value("${bvisionry.encryption.secret-key:}") String encryptionKey) {
        this.environment = environment;
        this.cookiesSecure = cookiesSecure;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prodActive = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prodActive) {
            return;
        }

        if (!cookiesSecure) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but bvisionry.security.cookies.secure=false. "
                            + "Auth cookies would be sent without the Secure flag — this usually means prod is "
                            + "running with dev configuration.");
        }
        if (jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but the JWT secret is empty or the built-in dev "
                            + "default. Set JWT_SECRET to a strong, unique value (>= 256 bits).");
        }
        if (encryptionKey == null || encryptionKey.isBlank() || DEV_ENCRYPTION_KEY.equals(encryptionKey)) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but the encryption key is empty or the built-in "
                            + "dev default. Set BVISIONRY_ENCRYPTION_KEY to a strong, unique value.");
        }

        log.info("StartupSafetyValidator: production safety checks passed (secure cookies + non-default secrets).");
    }
}
