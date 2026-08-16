package com.bvisionry.programflow.dto;

import java.time.Instant;
import java.util.UUID;

/** One org's participation in a cohort, for the assign-orgs panel. */
public record CohortOrgDto(
        UUID orgId,
        String orgName,
        /** "Parent -> Sub" display prefix; null for a root org. */
        String parentName,
        boolean autoEnroll,
        Instant assignedAt,
        int enrolledCount) {
}
