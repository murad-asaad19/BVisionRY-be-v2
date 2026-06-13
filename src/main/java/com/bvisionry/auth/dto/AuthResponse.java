package com.bvisionry.auth.dto;

public record AuthResponse(
        UserResponse user,
        String token,
        String refreshToken
) {}
