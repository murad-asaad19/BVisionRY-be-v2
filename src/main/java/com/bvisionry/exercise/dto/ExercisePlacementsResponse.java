package com.bvisionry.exercise.dto;

import java.util.List;
import java.util.UUID;

/**
 * Everywhere one exercise template is currently in use — the builder list's
 * "where is this assigned?" answer. Three independent surfaces can hand the
 * same template out, so they are three independent lists rather than one
 * flattened feed:
 *
 * <ul>
 *   <li>{@code organizations} — the org console grain: a provision, plus the
 *       members it was distributed to directly (V173 untagged rows only).</li>
 *   <li>{@code cohorts} — cohort board tasks referencing this template; their
 *       assignment rows are spawned lazily per learner, so the TASK is the
 *       placement, not the (possibly still empty) assignment rows.</li>
 *   <li>{@code publicLink} — the anonymous QR/link surface.</li>
 * </ul>
 */
public record ExercisePlacementsResponse(
        List<OrgPlacement> organizations,
        List<CohortPlacement> cohorts,
        /** Public link open right now (the template's own flag). */
        boolean publicLink
) {
    /** One org holding a provision, with the members it was handed to directly. */
    public record OrgPlacement(UUID organizationId, String organizationName, List<AssignedMember> members) {
        public record AssignedMember(UUID id, String name, String email) {}
    }

    /** One cohort board task pointing at this template. */
    public record CohortPlacement(
            UUID cohortId,
            String cohortName,
            String moduleName,
            String taskName,
            /** False = still a DRAFT card, invisible to learners. */
            boolean live
    ) {}
}
