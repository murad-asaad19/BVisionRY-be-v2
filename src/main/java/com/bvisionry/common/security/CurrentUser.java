package com.bvisionry.common.security;

import java.util.UUID;

/**
 * Feature-neutral view of the authenticated principal.
 *
 * <p>Exists so new feature slices can learn "who is calling" without importing
 * the {@code auth} feature (the ArchUnit ratchet forbids new cross-feature
 * dependencies). The adapter lives in {@code config} (shared wiring layer).
 *
 * @param userId the authenticated user's id
 * @param orgId  the user's organization id ({@code null} for org-less users)
 * @param name   display name
 * @param role   the {@code UserRole} name (e.g. {@code SUPER_ADMIN})
 */
public record CurrentUser(UUID userId, UUID orgId, String name, String role) {
}
