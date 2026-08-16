package com.bvisionry.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope encryption for secrets the platform stores on behalf of a
 * customer, keyed from {@code bvisionry.encryption.secret-key}.
 *
 * <p>Formerly {@code aiconfig.service.ApiKeyEncryptionService}. It lives in the
 * shared kernel because it now has two consumers in two features — AI provider
 * keys ({@code aiconfig}) and per-tenant OIDC client secrets ({@code auth.sso}) —
 * and a feature may not import another feature. The cipher itself is unchanged:
 * same algorithm, same random 12-byte IV, same key.
 *
 * <h2>Key versions, and why the prefix exists</h2>
 * {@code encrypt} stamps {@link #CURRENT_KEY_VERSION} on every value it writes.
 * The prefix does nothing today — there is exactly one key — and that is the
 * point: it is the thing that has to be present BEFORE a rotation, not after.
 * {@code application-prod.properties} documents that rotating
 * {@code BVISIONRY_ENCRYPTION_KEY} makes stored values undecryptable. With the
 * stamp, a rotation is a BACKFILL: every row still carrying an older version is
 * identifiable by a {@code LIKE 'v1:%'} scan and can be re-encrypted, and
 * {@link #decrypt} refuses a version it has no key for with a message naming the
 * version, instead of returning garbage or a misleading "corrupt data".
 *
 * <p>Without the stamp that scan is impossible: ciphertext is opaque, so nothing
 * distinguishes "encrypted under the old key" from "encrypted under the new one"
 * and the only signal is a failed decrypt at use time — for OIDC that is an
 * enterprise SSO outage discovered by the customer.
 *
 * <p><strong>Unversioned values stay readable.</strong> Every AI provider key
 * written before this change carries no prefix; those decrypt with the current
 * key exactly as they always did. Versioning is additive, so this change cannot
 * itself be the thing that breaks a stored secret.
 */
@Service
public class SecretEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    /**
     * The key {@code bvisionry.encryption.secret-key} resolves to today. Bump this
     * (and keep the previous key available) when a rotation is actually performed;
     * until then a bump would strand every stored value.
     */
    static final String CURRENT_KEY_VERSION = "v1";

    private static final String VERSION_SEPARATOR = ":";
    private static final String CURRENT_KEY_PREFIX = CURRENT_KEY_VERSION + VERSION_SEPARATOR;

    private final SecretKeySpec secretKey;

    public SecretEncryptionService(@Value("${bvisionry.encryption.secret-key}") String hexKey) {
        // VALIDATE THE ALPHABET, not just the length. `Character.digit(c, 16)` returns
        // -1 for a non-hex character, and `(-1 << 4) + (-1)` is 0xEF — so a 64-character
        // BASE64 key (a very plausible way to generate one) parses without error into
        // large runs of identical predictable bytes, and 64 'z' characters yields a key
        // of 32 * 0xEF with no complaint at all. The length check alone cannot see it.
        // This matters more than it did: the same key now protects enterprise SSO client
        // secrets, and V155's header claims the encryption is real against a stolen
        // backup — which is only true if the key is what the operator thinks it is.
        // Fail at startup, loudly, rather than encrypting with a mangled key.
        if (hexKey == null || !hexKey.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException(
                    "bvisionry.encryption.secret-key must be exactly 64 hexadecimal characters "
                            + "(32 bytes for AES-256). Generate one with: openssl rand -hex 32");
        }
        byte[] keyBytes = hexStringToBytes(hexKey);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a random IV.
     * Returns {@code "<keyVersion>:"} + Base64 of IV (12 bytes) + ciphertext + GCM tag.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return CURRENT_KEY_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}, with or without a key-version
     * prefix (an unprefixed value predates versioning and used the same key).
     *
     * @throws RuntimeException on any failure — including a value stamped with a key
     *                          version this process has no key for. Callers already
     *                          treat a failed decrypt as "not configured" / fail
     *                          closed; the message is what tells an operator which
     *                          rotation stranded the row.
     */
    public String decrypt(String stored) {
        int separator = versionSeparatorIndex(stored);
        if (separator >= 0) {
            String version = stored.substring(0, separator);
            if (!CURRENT_KEY_VERSION.equals(version)) {
                throw new IllegalStateException("Stored secret was encrypted with key version " + version
                        + " but bvisionry.encryption.secret-key is version " + CURRENT_KEY_VERSION
                        + ". Restore that key and re-encrypt the row, or re-enter the secret.");
            }
        }
        String payload = separator >= 0 ? stored.substring(separator + 1) : stored;
        try {
            byte[] combined = Base64.getDecoder().decode(payload);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    /**
     * True when {@code value} carries a key-version prefix, i.e. it was written by
     * {@link #encrypt} at or after this change.
     *
     * <p><strong>This is not "is this encrypted".</strong> It answers the narrower
     * question a column that used to hold PLAINTEXT needs — "has this row been
     * converted yet" — and returns {@code false} for the perfectly valid UNVERSIONED
     * ciphertext written before versioning existed (every AI provider key stored to
     * date). Only a caller whose column has never held ciphertext may read a
     * {@code false} as "plaintext"; {@code auth.sso} is the one such caller, and
     * {@code V155} is the migration that makes that true of it.
     */
    public static boolean isVersioned(String value) {
        return versionSeparatorIndex(value) >= 0;
    }

    /**
     * Index of the {@code ':'} closing a {@code v<digits>} prefix, or -1. Base64
     * never contains {@code ':'}, so a match is unambiguous and an unprefixed
     * legacy ciphertext can never be mistaken for a versioned one.
     */
    private static int versionSeparatorIndex(String value) {
        if (value == null || value.length() < 3 || value.charAt(0) != 'v') {
            return -1;
        }
        int i = 1;
        while (i < value.length() && Character.isDigit(value.charAt(i))) {
            i++;
        }
        return i > 1 && i < value.length() && value.charAt(i) == ':' ? i : -1;
    }

    /**
     * Masks an API key for display, showing prefix + last 4 characters.
     * Example: "sk-or-v1-abc123def456" -> "sk-or-***f456"
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null) return null;
        if (apiKey.length() <= 4) return "***" + apiKey;

        // Find a reasonable prefix (up to first dash after "sk-or" or first 5 chars)
        int prefixEnd = Math.min(5, apiKey.length() - 4);
        String prefix = apiKey.substring(0, prefixEnd);
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "***" + suffix;
    }

    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
