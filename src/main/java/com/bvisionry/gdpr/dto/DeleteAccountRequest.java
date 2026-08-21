package com.bvisionry.gdpr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Confirmation for the irreversible GDPR Art. 17 erasure.
 *
 * <p>{@code confirmEmail} captures intent — the caller retypes their own
 * address, checked server-side against the authenticated account, so a
 * mis-wired client cannot delete an account by accident.
 *
 * <p>{@code currentPassword} is the re-authentication step, required for every
 * account that HAS a password. Deleting an account is irreversible, so it must
 * not demand less proof than changing a recoverable password does;
 * {@code AuthService.changePassword} makes the same check for the same stated
 * reason (a hijacked session must not act alone). SSO-only accounts have no
 * hash to verify and are confirmed by email alone — the same asymmetry
 * {@code AuthService} already documents.
 *
 * @param confirmEmail    the caller's own email address, compared case-insensitively
 * @param currentPassword the caller's password; omitted for SSO-only accounts
 */
public record DeleteAccountRequest(
        @NotBlank
        @Schema(description = "The caller's own email address, retyped to confirm intent",
                example = "founder@example.com")
        String confirmEmail,

        @Schema(description = "The caller's current password. Required unless the account is SSO-only.")
        String currentPassword) {
}
