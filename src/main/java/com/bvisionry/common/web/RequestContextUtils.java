package com.bvisionry.common.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Pure utilities for request-scoped context. For client-IP resolution that
 * honors trusted proxies, use {@link ClientIpResolver} instead — historic
 * static {@code getClientIp} was vulnerable to XFF spoofing and has been
 * removed.
 */
public final class RequestContextUtils {

    private RequestContextUtils() {}

    public static String sha256Hex(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
