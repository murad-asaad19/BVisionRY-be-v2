package com.bvisionry.auth.jwt;

/**
 * Distinguishes access tokens from refresh and download tokens. The enum's
 * {@code name()} is written into the {@code typ} JWT claim by
 * {@link JwtProvider}, and {@link JwtProvider#validateToken(String, TokenType)}
 * rejects tokens whose {@code typ} does not match the expected type. This
 * prevents an access token from being replayed against {@code /api/auth/refresh},
 * a refresh token from satisfying the access filter, or a download token from
 * standing in for either.
 */
public enum TokenType {
    ACCESS,
    REFRESH,
    DOWNLOAD
}
