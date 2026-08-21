package com.bvisionry.auth.jwt;

/**
 * Distinguishes access tokens from refresh tokens. The enum's {@code name()}
 * is written into the {@code typ} JWT claim by {@link JwtProvider}, and
 * {@link JwtProvider#validateToken(String, TokenType)} rejects tokens whose
 * {@code typ} does not match the expected type. This prevents an access token
 * from being replayed against {@code /api/auth/refresh}, or a refresh token
 * from satisfying the access filter.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
