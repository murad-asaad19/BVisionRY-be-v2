package com.bvisionry.coaching.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Create one coach grant: exactly one of {@code cohortId} (whole-cohort) or
 * {@code memberId} (direct founder) — the service rejects both/neither.
 */
public record CreateCoachAssignmentRequest(
        @NotNull UUID coachId,
        UUID cohortId,
        UUID memberId) {
}
