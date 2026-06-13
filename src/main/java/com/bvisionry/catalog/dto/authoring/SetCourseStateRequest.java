package com.bvisionry.catalog.dto.authoring;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the course state-transition endpoint
 * ({@code POST /api/v1/courses/{slug}/state}). {@code state} must be one of
 * DRAFT / PUBLISHED / ARCHIVED.
 */
public record SetCourseStateRequest(@NotBlank String state) {
}
