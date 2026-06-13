package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH-style update for an org member's lightweight profile fields.
 *
 * Both fields are optional; null means "leave unchanged" so the same payload
 * shape works for inline name edits and member-type dropdown changes from the
 * org admin UI without forcing the FE to round-trip the unchanged value.
 *
 * Lives on the org-scoped /api/organizations/{orgId}/members/{memberId}/profile
 * endpoint specifically so ORG_ADMINs can edit their own org's members; the
 * legacy super-admin-only PUT /api/users/{id} stays as-is.
 */
public record UpdateMemberProfileRequest(
        @Size(max = 255, message = "Name must be 255 characters or less")
        String name,
        @Size(max = 64, message = "Member type code must be 64 characters or less")
        String userType
) {}
