package com.bvisionry.assessment.dto;

import java.util.UUID;

/**
 * Pillar structure only — id/name/type, no question or answer content.
 * Powers the unlock-pillars picker, which needs to know which pillars exist
 * on the assignment's pipeline without exposing what the member answered
 * (that's {@link AssessmentDetailResponse}, restricted to the Super Admin).
 */
public record PillarSummaryResponse(
        UUID id,
        String name,
        String type
) {}
