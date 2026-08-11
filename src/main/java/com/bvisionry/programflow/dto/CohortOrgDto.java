package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One org's participation in a cohort, for the assign-orgs panel. */
public record CohortOrgDto(
        UUID orgId,
        String orgName,
        boolean autoEnroll,
        OffsetDateTime assignedAt,
        int enrolledCount) {
}
