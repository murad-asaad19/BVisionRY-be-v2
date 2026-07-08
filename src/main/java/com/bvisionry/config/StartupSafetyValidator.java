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
 * or committed-fallback JWT secret, encryption key, or proxy shared secret, or
 * {@code cookies.secure=false}. This complements the image-level default profile
 * in the Dockerfile.
 */
@Component
@Slf4j
public class StartupSafetyValidator implements InitializingBean {

    /** The non-secret dev defaults shipped in application.properties — never valid in prod. */
    private static final String DEV_JWT_SECRET =
            "bvisionry-dev-secret-key-change-in-production-must-be-at-least-256-bits-long";
    private static final String DEV_ENCRYPTION_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * The committed prod-profile fallbacks that used to live in application-prod.properties.
     * They have since been removed (the prod profile now fails closed on an unset env var),
     * but they are blocklisted here as defense-in-depth: re-adding any of them must not boot prod.
     */
    private static final String PROD_FALLBACK_JWT_SECRET =
            "a3f17c0e9b425d681f4e8a26c09d7b536b0d92f4e7a1c35880c5e213a9f4d76b";
    private static final String PROD_FALLBACK_ENCRYPTION_KEY =
            "d7e2a91f3c60b4850a6c4e8d25f7b139e4810b53d9a2c70f5f3e8c10a7b6d924";

    /** The committed default proxy shared secret (dev base + BFF literal) — never valid in prod. */
    private static final String DEFAULT_PROXY_SHARED_SECRET =
            "default-bvisionry-proxy-secret-9f2c7a4e1d8b6053f4a9c2e7b1d6480a";

    private final Environment environment;
    private final boolean cookiesSecure;
    private final String jwtSecret;
    private final String encryptionKey;
    private final String proxySharedSecret;

    public StartupSafetyValidator(
            Environment environment,
            @Value("${bvisionry.security.cookies.secure:false}") boolean cookiesSecure,
            @Value("${bvisionry.jwt.secret:}") String jwtSecret,
            @Value("${bvisionry.encryption.secret-key:}") String encryptionKey,
            @Value("${bvisionry.proxy.shared-secret:}") String proxySharedSecret) {
        this.environment = environment;
        this.cookiesSecure = cookiesSecure;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.proxySharedSecret = proxySharedSecret;
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
        if (jwtSecret == null || jwtSecret.isBlank()
                || DEV_JWT_SECRET.equals(jwtSecret) || PROD_FALLBACK_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but the JWT secret is empty, the built-in dev "
                            + "default, or a committed prod fallback. Set JWT_SECRET to a strong, unique value "
                            + "(>= 256 bits).");
        }
        if (encryptionKey == null || encryptionKey.isBlank()
                || DEV_ENCRYPTION_KEY.equals(encryptionKey) || PROD_FALLBACK_ENCRYPTION_KEY.equals(encryptionKey)) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but the encryption key is empty, the built-in "
                            + "dev default, or a committed prod fallback. Set BVISIONRY_ENCRYPTION_KEY to a strong, "
                            + "unique value.");
        }
        if (proxySharedSecret == null || proxySharedSecret.isBlank()
                || DEFAULT_PROXY_SHARED_SECRET.equals(proxySharedSecret)) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but the proxy shared secret is empty or the "
                            + "committed default. Set BVISIONRY_PROXY_SHARED_SECRET to a strong, unique value "
                            + "matching the BFF's BFF_PROXY_SHARED_SECRET.");
        }

        log.info("StartupSafetyValidator: production safety checks passed (secure cookies + non-default secrets).");
    }
}
