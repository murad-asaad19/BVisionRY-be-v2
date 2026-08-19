package com.bvisionry.cohortview.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The dedicated cohort view's header + overview panel (redesign spec §13.7).
 *
 * <p><strong>Org slice, not cohort total (§13.7).</strong> A cohort is a
 * platform artifact assigned to one or more orgs, so every member-derived
 * number here counts only the CALLING org's slice of the roster. Two orgs
 * running the same cohort each see their own completion, their own activity —
 * never each other's founders.
 */
public record CohortOverviewResponse(
        UUID cohortId,
        String name,
        /** The operator's theme line (V176), null when none was written. */
        String description,
        String status,
        Instant createdAt,
        /**
         * When the cohort went live — {@code cohorts.launched_at} as a date, the
         * only start the schema records; null while the cohort is a DRAFT.
         */
        LocalDate startAt,
        /**
         * The program's end date ({@code program_settings.end_at}, per cohort
         * since V122), null when the operator set none.
         */
        LocalDate endAt,
        /**
         * Seats bought for this cohort. ALWAYS NULL today: the V167 quota model
         * meters LAUNCHES per billing-root org, not seats per cohort, so no
         * per-cohort seat count exists to report. Kept in the contract because
         * the panel has a slot for it the day one lands.
         */
        Integer seatsTotal,
        /**
         * The header's measurement-pair chip (spec §5): the baseline and
         * distance instrument names off {@code program_settings}, both null
         * until an admin designates the pair on the Curriculum tab's Distance
         * card — never partially rendered as an empty chip.
         */
        String baselinePipelineName,
        String distancePipelineName,
        /**
         * The same pair's short codes (V201) — what the People roster's growth
         * column is headed with ("MRA → MDA"). Null when the pipeline carries
         * no abbreviation; the reader falls back to the role name.
         */
        String baselinePipelineAbbrev,
        String distancePipelineAbbrev,
        List<CohortCoach> coaches,
        List<Milestone> milestones,
        List<CohortActivityItem> activity) {

    /**
     * A coach on this cohort: either a whole-cohort grant on THIS cohort, or an
     * org-wide grant (V176) that covers it along with everything else.
     * {@code orgWide} is true only when the coach holds NO cohort grant here —
     * a coach with both is shown as this cohort's coach.
     */
    public record CohortCoach(UUID id, String name, boolean orgWide) {}

    /**
     * One milestone task of the cohort's LIVE program (BASELINE / CHECKIN /
     * DISTANCE, {@code program_tasks.milestone_role}, V164), in board order.
     *
     * <p>{@code totalMembers} is the org slice's roster narrowed to the
     * module's AUDIENCE — a member the module never reached cannot be behind on
     * it, and counting them would deflate every cohort that uses a targeted
     * module.
     */
    public record Milestone(UUID taskId, String name, String role, LocalDate dueDate,
                            int doneCount, int totalMembers) {}

    /**
     * One recent event by a member of the org slice. {@code type} is
     * TASK_SUBMITTED | EXERCISE_SUBMITTED | ASSESSMENT_EVALUATED |
     * SESSION_ATTENDED; {@code title} is the task / template / pipeline /
     * session name.
     */
    public record CohortActivityItem(String type, String memberName, String title, Instant at) {}
}
