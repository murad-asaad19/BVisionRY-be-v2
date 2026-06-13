package com.bvisionry.auth.dto;

/**
 * Response shape for {@code GET /api/auth/download-token}. The SPA appends
 * {@code token} to its direct-to-Railway PDF/XLSX URLs and uses {@code baseUrl}
 * as the origin (so the FE doesn't hardcode the Railway hostname).
 */
public record DownloadTokenResponse(String token, String baseUrl, long expiresInSeconds) {}
