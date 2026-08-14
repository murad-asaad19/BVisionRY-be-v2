package com.bvisionry.cohortview.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The dedicated cohort view's roster table (redesign spec §13.7) — the calling
 * org's slice of the cohort only, ordered by name.
 *
 * <p><strong>Deliberately not here.</strong> Participation lives at
 * {@code GET /api/organizations/{orgId}/cohorts/{cohortId}/participation} and
 * growth summaries at {@code …/cohorts/{cohortId}/comparisons}. The table
 * composes those client-side; duplicating either read would give the same
 * screen two sources for one number.
 */
public record CohortRosterResponse(List<CohortRosterMember> members) {

    public record CohortRosterMember(
            UUID userId,
            String name,
            String email,
            String memberType,
            /** Latest EVALUATED overall on THIS COHORT'S OWN instruments — null when none. */
            BigDecimal friLatest,
            /** {@code friLatest} minus the previous sitting on those instruments; null until two exist. */
            BigDecimal friDelta,
            /** LIVE tasks of this cohort in the member's module audience, done / total. */
            int progressDone,
            int progressTotal,
            /** Audience tasks past their due date and still not done. */
            int overdueCount,
            /** SUBMITTED exercise submissions on this cohort's tasks, waiting on a review. */
            int awaitingReview,
            Instant lastActivityAt) {}
}
