package com.bvisionry.programflow.dto;

import java.util.UUID;

/**
 * One enrolled founder as the BUILDER needs them: a name to pick from when
 * setting a module's audience, labelled by org because a platform cohort's
 * roster may span orgs (spec §13). Carries no progress — that is org data and
 * lives on the org console (§13.7).
 */
public record CohortRosterEntryDto(
        UUID userId,
        String name,
        String email,
        UUID orgId,
        String orgName) {
}
