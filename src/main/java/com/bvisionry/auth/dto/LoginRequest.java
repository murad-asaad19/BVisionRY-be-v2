package com.bvisionry.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The email is length-bounded as well as format-checked. {@code @Email} alone accepts
 * an arbitrarily long local part, and this is an anonymous endpoint whose email becomes
 * a per-account rate-limit KEY — an unbounded address would mint unbounded Redis keys
 * (two per address, each held for the failure TTL) plus a heap entry in the in-memory
 * fallback. 254 is the RFC 5321 ceiling for a deliverable address.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {}
