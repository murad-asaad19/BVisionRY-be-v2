package com.bvisionry.membertype.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update. {@code code} is intentionally immutable — it's stored on
 * users.user_type and referenced throughout the app, so renaming it would
 * silently break those links.
 */
public record UpdateMemberTypeRequest(
        @Size(max = 128, message = "Label must be 128 characters or less")
        String label,
        Integer displayOrder
) {}
