package com.bvisionry.courseaccess.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Super-admin only. {@code minTier} is required for MIN_TIER and ignored
 * otherwise; {@code orgIds} replaces the whole list for ORG_LIST.
 */
public record UpdateCourseVisibilityRequest(@NotNull String visibility, String minTier, List<UUID> orgIds) {
}
