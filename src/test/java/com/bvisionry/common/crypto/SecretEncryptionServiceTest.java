package com.bvisionry.common.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretEncryptionServiceTest {

    private SecretEncryptionService encryptionService;

    // 32-byte hex key for AES-256
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        encryptionService = new SecretEncryptionService(TEST_SECRET);
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
    void encrypt_thenDecrypt_roundTripsNonAsciiRegardlessOfPlatformCharset() {
        // The service pins UTF-8 explicitly — a platform default other than
        // UTF-8 must not corrupt non-ASCII key material.
        String original = "sk-ключ-密钥-🔑";
        assertThat(encryptionService.decrypt(encryptionService.encrypt(original)))
                .isEqualTo(original);
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

    // ------------------------------------------------------------- key versions

    @Test
    void encrypt_stampsTheCurrentKeyVersionSoARotationCanBeBackfilled() {
        // The whole point of the prefix: after a rotation, "which rows still use the
        // old key" must be a WHERE clause, not a guess. Ciphertext is opaque, so the
        // stamp is the only thing that can answer it — and it has to be there BEFORE
        // the rotation, not added after.
        String encrypted = encryptionService.encrypt("super-secret");

        assertThat(encrypted).startsWith("v1:");
        assertThat(SecretEncryptionService.isVersioned(encrypted)).isTrue();
    }

    @Test
    void decrypt_readsAnUnversionedLegacyValueWrittenBeforeVersioningExisted() {
        // Every AI provider key stored to date has NO prefix. If this branch broke,
        // adding versioning would itself be the thing that stranded them.
        String legacy = encryptionService.encrypt("sk-or-v1-legacy").substring("v1:".length());

        assertThat(SecretEncryptionService.isVersioned(legacy))
                .as("unversioned ciphertext must not be mistaken for a converted row")
                .isFalse();
        assertThat(encryptionService.decrypt(legacy)).isEqualTo("sk-or-v1-legacy");
    }

    @Test
    void decrypt_refusesAValueStampedWithAKeyVersionThisProcessHasNoKeyFor() {
        // The failure a rotation produces. It must name the version rather than read
        // as "corrupt data", because the recovery (restore that key, re-encrypt) is
        // completely different from the recovery for a corrupt row.
        String rotated = "v2:" + encryptionService.encrypt("super-secret").substring("v1:".length());

        assertThatThrownBy(() -> encryptionService.decrypt(rotated))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("v2")
                .hasMessageContaining("v1");
    }

    @Test
    void isVersioned_isFalseForPlaintextThatMerelyLooksLikeAPrefix() {
        // The column auth.sso converts held ARBITRARY customer-issued secrets. A
        // secret that happens to start with a letter and a colon must not be read as
        // a version stamp, or the login path would try to decrypt plaintext.
        assertThat(SecretEncryptionService.isVersioned("verysecret")).isFalse();
        assertThat(SecretEncryptionService.isVersioned("v:abc")).isFalse();
        assertThat(SecretEncryptionService.isVersioned("value:abc")).isFalse();
        assertThat(SecretEncryptionService.isVersioned("2v:abc")).isFalse();
        assertThat(SecretEncryptionService.isVersioned(null)).isFalse();
        assertThat(SecretEncryptionService.isVersioned("")).isFalse();
    }

    // -------------------------------------------------------------------- masking

    @Test
    void maskApiKey_showsLastFourChars() {
        String key = "sk-or-v1-abc123def456";
        String masked = SecretEncryptionService.maskApiKey(key);

        assertThat(masked).endsWith("f456");
        assertThat(masked).startsWith("sk-or***");
    }

    @Test
    void maskApiKey_shortKey_returnsMasked() {
        String key = "abc";
        String masked = SecretEncryptionService.maskApiKey(key);

        assertThat(masked).isEqualTo("***abc");
    }

    @Test
    void maskApiKey_null_returnsNull() {
        assertThat(SecretEncryptionService.maskApiKey(null)).isNull();
    }
}
