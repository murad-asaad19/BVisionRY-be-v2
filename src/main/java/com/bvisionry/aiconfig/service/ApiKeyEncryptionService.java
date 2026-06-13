package com.bvisionry.aiconfig.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec secretKey;

    public ApiKeyEncryptionService(@Value("${bvisionry.encryption.secret-key}") String hexKey) {
        byte[] keyBytes = hexStringToBytes(hexKey);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("Encryption key must be 64 hex characters (32 bytes) for AES-256");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a random IV.
     * Returns Base64-encoded string: IV (12 bytes) + ciphertext + GCM tag.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes());

            // Prepend IV to ciphertext
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM ciphertext (IV prepended).
     */
    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
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
