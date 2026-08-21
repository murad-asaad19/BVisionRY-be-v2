package com.bvisionry.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

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

    /**
     * The committed dev-default web-push VAPID private key and MinIO secret from
     * application.properties. Blocklisted so a PARTIAL prod env (env vars set for
     * the big three but not these) can't silently run production push/storage on
     * publicly-committed credentials. Blank is allowed — that's "feature not
     * configured", which fails loudly in the feature itself, not here.
     */
    private static final String DEV_VAPID_PRIVATE_KEY = "RAv88777_39RhxvBROLQluAktjHlu59rfp0F5QdZBrg";
    private static final String DEV_MINIO_SECRET_KEY = "minio123";

    private final Environment environment;
    private final boolean cookiesSecure;
    private final String jwtSecret;
    private final String encryptionKey;
    private final String proxySharedSecret;
    private final String vapidPrivateKey;
    private final String minioSecretKey;
    private final boolean aiMockEnabled;

    public StartupSafetyValidator(
            Environment environment,
            @Value("${bvisionry.security.cookies.secure:false}") boolean cookiesSecure,
            @Value("${bvisionry.jwt.secret:}") String jwtSecret,
            @Value("${bvisionry.encryption.secret-key:}") String encryptionKey,
            @Value("${bvisionry.proxy.shared-secret:}") String proxySharedSecret,
            @Value("${bvisionry.push.vapid.private-key:}") String vapidPrivateKey,
            @Value("${bvisionry.minio.secret-key:}") String minioSecretKey,
            @Value("${bvisionry.ai.mock.enabled:false}") boolean aiMockEnabled) {
        this.environment = environment;
        this.cookiesSecure = cookiesSecure;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.proxySharedSecret = proxySharedSecret;
        this.vapidPrivateKey = vapidPrivateKey;
        this.minioSecretKey = minioSecretKey;
        this.aiMockEnabled = aiMockEnabled;
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
        // The AI transport is not a secret, so it gets its own check: the failure
        // it prevents is customers being served CANNED narratives from a green,
        // fully healthy-looking deployment. Nothing else would report it — the
        // mock answers every call successfully. This became reachable when the
        // switch moved from "remember to add the mock profile" to a property,
        // because `application-mock.properties` is profile-specific and so beats
        // `application-prod.properties` on a `prod,mock` boot, and because any
        // BVISIONRY_AI_MOCK_ENABLED=true in the environment wins outright.
        if (aiMockEnabled) {
            throw new IllegalStateException(
                    "Refusing to start: 'prod' profile is active but bvisionry.ai.mock.enabled=true. "
                            + "Every AI result would be canned static text with no provider call, and nothing "
                            + "downstream would report it. Remove the 'mock' profile from SPRING_PROFILES_ACTIVE "
                            + "and unset BVISIONRY_AI_MOCK_ENABLED.");
        }
        // One row per guarded secret: adding the next one is a single entry here.
        // requiredInProd=false means blank is acceptable ("feature not configured").
        List<SecretCheck> checks = List.of(
                new SecretCheck(jwtSecret, true, Set.of(DEV_JWT_SECRET, PROD_FALLBACK_JWT_SECRET),
                        "Refusing to start: 'prod' profile is active but the JWT secret is empty, the built-in dev "
                                + "default, or a committed prod fallback. Set JWT_SECRET to a strong, unique value "
                                + "(>= 256 bits)."),
                new SecretCheck(encryptionKey, true, Set.of(DEV_ENCRYPTION_KEY, PROD_FALLBACK_ENCRYPTION_KEY),
                        "Refusing to start: 'prod' profile is active but the encryption key is empty, the built-in "
                                + "dev default, or a committed prod fallback. Set BVISIONRY_ENCRYPTION_KEY to a strong, "
                                + "unique value."),
                new SecretCheck(proxySharedSecret, true, Set.of(DEFAULT_PROXY_SHARED_SECRET),
                        "Refusing to start: 'prod' profile is active but the proxy shared secret is empty or the "
                                + "committed default. Set BVISIONRY_PROXY_SHARED_SECRET to a strong, unique value "
                                + "matching the BFF's BFF_PROXY_SHARED_SECRET."),
                new SecretCheck(vapidPrivateKey, false, Set.of(DEV_VAPID_PRIVATE_KEY),
                        "Refusing to start: 'prod' profile is active but the web-push VAPID private key is the "
                                + "committed dev default. Set VAPID_PRIVATE_KEY (and VAPID_PUBLIC_KEY) to a freshly "
                                + "generated pair."),
                new SecretCheck(minioSecretKey, false, Set.of(DEV_MINIO_SECRET_KEY),
                        "Refusing to start: 'prod' profile is active but the MinIO secret key is the committed dev "
                                + "default ('minio123'). Set MINIO_SECRET_KEY to the production object-store credential."));

        for (SecretCheck check : checks) {
            boolean blank = check.value() == null || check.value().isBlank();
            if (blank ? check.requiredInProd() : check.blockedValues().contains(check.value())) {
                throw new IllegalStateException(check.message());
            }
        }

        log.info("StartupSafetyValidator: production safety checks passed "
                + "(secure cookies + non-default secrets + live AI transport).");
    }

    private record SecretCheck(String value, boolean requiredInProd, Set<String> blockedValues, String message) {}
}
