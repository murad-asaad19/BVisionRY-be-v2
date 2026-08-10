package com.bvisionry.programflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.domain.SubmissionStatus;

/** The learner journey for one cohort: audience-filtered modules with drip state + my progress. */
public record JourneyResponse(
        ProgramSettingsDto settings,
        Progress progress,
        GamificationDto gamification,
        List<JourneyModule> modules,
        UUID cohortId,
        boolean readOnly,
        /**
         * How many members the cohort has (spec §11: the journey hero shows
         * cohort size). 0 when the member has no cohort. Week x-of-y and the
         * progress % are derived client-side from modules + progress.
         */
        int memberCount,
        /**
         * The member's work NOT attached to any cohort task (spec §2.1) —
         * present even when the member has no cohort at all.
         */
        List<DirectAssignmentDto> directAssignments) {

    public record Progress(int done, int total) {
    }

    public record JourneyModule(
            UUID id,
            String name,
            String summary,
            LockState lockState,
            OffsetDateTime unlockAt,
            String previousModuleName,
            List<JourneyTask> tasks) {
    }

    /**
     * One typed task row. {@code myStatus} is the legacy LESSON submission
     * status (kept for the existing player UI); {@code state} is the unified
     * cross-type vocabulary every consumer should prefer. ASSESSMENT tasks
     * carry their milestone role so the journey can render check-in bands
     * between modules, plus the payoff pair ({@code scoreBefore} →
     * {@code score}) once evaluated (spec §2.1/§5).
     */
    public record JourneyTask(
            UUID id,
            String name,
            LocalDate dueDate,
            int questions,
            int steps,
            SubmissionStatus myStatus,
            ProgramTaskType taskType,
            UUID refId,
            MilestoneRole milestoneRole,
            JourneyTaskState state,
            /** Course progress 0–100; null for other types. */
            Integer progressPct,
            /** Evaluated assessment overall score (the payoff "after"); null otherwise. */
            BigDecimal score,
            /** The previous evaluated milestone's score (the payoff "before"); null when none. */
            BigDecimal scoreBefore,
            /** §7b: when the member submitted/completed their side, where applicable. */
            Instant submittedAt,
            Instant completedAt,
            /**
             * COURSE only (spec §3 downgrade policy): the course is no longer
             * visible to this member's organization. The row renders with a
             * "no longer available" state, opening it is refused, and it gates
             * nothing — progress and any certificate are untouched.
             */
            boolean unavailable) {
    }

    public enum LockState {
        UNLOCKED,
        LOCKED_SEQUENTIAL,
        LOCKED_SCHEDULED
    }
}
