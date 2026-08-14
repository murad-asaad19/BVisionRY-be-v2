package com.bvisionry.coaching.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Create one coach grant at one of three grains: {@code cohortId} (whole
 * cohort), {@code memberId} (direct founder), or NEITHER — the ORG-WIDE grant
 * (V176), every active member of the org. The service rejects only BOTH, which
 * is not a grain.
 */
public record CreateCoachAssignmentRequest(
        @NotNull UUID coachId,
        UUID cohortId,
        UUID memberId) {
}
