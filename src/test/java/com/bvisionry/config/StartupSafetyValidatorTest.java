package com.bvisionry.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed contract of the prod boot guard: every committed dev-default
 * credential — JWT, encryption key, proxy secret, and (the two that used to be
 * missing from the blocklist) the VAPID private key and MinIO secret — must
 * refuse to boot the {@code prod} profile, while dev boots freely on all of them.
 */
class StartupSafetyValidatorTest {

    private static final String DEV_JWT =
            "bvisionry-dev-secret-key-change-in-production-must-be-at-least-256-bits-long";
    private static final String DEV_ENC_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DEV_VAPID_PRIVATE = "RAv88777_39RhxvBROLQluAktjHlu59rfp0F5QdZBrg";
    private static final String DEV_MINIO_SECRET = "minio123";

    private static final String GOOD_JWT = "a-strong-unique-jwt-secret-that-is-definitely-long-enough-123456";
    private static final String GOOD_ENC = "f".repeat(64);
    private static final String GOOD_PROXY = "unique-proxy-secret";
    private static final String GOOD_VAPID = "unique-vapid-private-key";
    private static final String GOOD_MINIO = "unique-minio-secret";

    private static MockEnvironment env(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private static StartupSafetyValidator validator(String profile, String vapidPrivate, String minioSecret) {
        return new StartupSafetyValidator(env(profile), true,
                GOOD_JWT, GOOD_ENC, GOOD_PROXY, vapidPrivate, minioSecret, false);
    }

    @Test
    void prodBootsWithUniqueSecrets() {
        assertThatCode(() -> validator("prod", GOOD_VAPID, GOOD_MINIO).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void prodRefusesDevVapidPrivateKey() {
        assertThatThrownBy(() -> validator("prod", DEV_VAPID_PRIVATE, GOOD_MINIO).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAPID");
    }

    @Test
    void prodRefusesDevMinioSecret() {
        assertThatThrownBy(() -> validator("prod", GOOD_VAPID, DEV_MINIO_SECRET).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO");
    }

    @Test
    void prodStillRefusesDevJwtAndEncryptionAndInsecureCookies() {
        assertThatThrownBy(() -> new StartupSafetyValidator(env("prod"), true,
                DEV_JWT, GOOD_ENC, GOOD_PROXY, GOOD_VAPID, GOOD_MINIO, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT");
        assertThatThrownBy(() -> new StartupSafetyValidator(env("prod"), true,
                GOOD_JWT, DEV_ENC_KEY, GOOD_PROXY, GOOD_VAPID, GOOD_MINIO, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("encryption");
        assertThatThrownBy(() -> new StartupSafetyValidator(env("prod"), false,
                GOOD_JWT, GOOD_ENC, GOOD_PROXY, GOOD_VAPID, GOOD_MINIO, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cookies");
    }

    @Test
    void devBootsOnAllDevDefaults() {
        assertThatCode(() -> new StartupSafetyValidator(env("dev"), false,
                DEV_JWT, DEV_ENC_KEY, "default-bvisionry-proxy-secret-9f2c7a4e1d8b6053f4a9c2e7b1d6480a",
                DEV_VAPID_PRIVATE, DEV_MINIO_SECRET, true).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
