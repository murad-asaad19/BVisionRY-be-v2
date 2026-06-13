package com.bvisionry.aiconfig.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyEncryptionServiceTest {

    private ApiKeyEncryptionService encryptionService;

    // 32-byte hex key for AES-256
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        encryptionService = new ApiKeyEncryptionService(TEST_SECRET);
    }

    @Test
    void encrypt_thenDecrypt_returnsOriginal() {
        String original = "sk-or-v1-abc123def456";
        String encrypted = encryptionService.encrypt(original);

        assertThat(encrypted).isNotEqualTo(original);
        assertThat(encrypted).isNotBlank();

        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encrypt_producesUniqueCiphertextEachTime() {
        String original = "sk-or-v1-abc123def456";
        String encrypted1 = encryptionService.encrypt(original);
        String encrypted2 = encryptionService.encrypt(original);

        // Different IVs should produce different ciphertexts
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    void decrypt_withCorruptedData_throwsException() {
        assertThatThrownBy(() -> encryptionService.decrypt("not-valid-base64-cipher"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void maskApiKey_showsLastFourChars() {
        String key = "sk-or-v1-abc123def456";
        String masked = ApiKeyEncryptionService.maskApiKey(key);

        assertThat(masked).endsWith("f456");
        assertThat(masked).startsWith("sk-or***");
    }

    @Test
    void maskApiKey_shortKey_returnsMasked() {
        String key = "abc";
        String masked = ApiKeyEncryptionService.maskApiKey(key);

        assertThat(masked).isEqualTo("***abc");
    }

    @Test
    void maskApiKey_null_returnsNull() {
        assertThat(ApiKeyEncryptionService.maskApiKey(null)).isNull();
    }
}
