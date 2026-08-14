package com.bvisionry.common.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Program-flow domain events, published by the {@code programflow} slice and
 * consumed by the {@code notification} slice's push handler. They live in
 * {@code common} so neither feature has to import the other (the architecture
 * rules forbid new cross-feature dependencies), which is why they carry plain
 * ids and display strings rather than entities.
 *
 * <p>The SUBMISSION events below ({@link TaskSubmitted}, {@link
 * AssessmentSubmitted}, {@link ExerciseSubmitted}, {@link
 * ExerciseFeedbackReplied}) are published by {@code programflow}, {@code
 * assessment} and {@code exercise} respectively — not just {@code
 * programflow} — and grouped here anyway: redesign spec §2.2 makes all three
 * submission types one review-work concept, delivered to org admins AND the
 * coaches covering the submitter ({@code CoachReviewNotifier}, a per-founder
 * lookup). Publishing an event rather than calling {@code notification.push}
 * straight from {@code assessment}/{@code exercise} keeps those two features
 * at ZERO dependency on it, same as {@code programflow}; all four are picked
 * up by the one {@code ProgramFlowPushHandler}.
 */
public final class ProgramFlowEvents {

    private ProgramFlowEvents() {
    }

    /** Learners were enrolled into a cohort (roster edit, org assignment or auto-enroll). */
    public record CohortEnrolled(String cohortName, List<UUID> userIds) {
    }

    /** A module's audience now includes these enrolled learners (admin assignment). */
    public record ModuleAssigned(String moduleName, String cohortName, List<UUID> userIds) {
    }

    /** A SCHEDULED module's unlock time passed for these enrolled learners. */
    public record ModuleUnlocked(String moduleName, List<UUID> userIds) {
    }

    /** A live task's due date is inside the cohort's due-soon window for these learners. */
    public record TaskDueSoon(UUID taskId, String taskName, LocalDate dueDate, List<UUID> userIds) {
    }

    /**
     * A learner submitted a program task (first submit only) — for org admins
     * and, per redesign spec §2.2, the coaches who cover {@code learnerId}.
     */
    public record TaskSubmitted(UUID orgId, UUID learnerId, String learnerName, String taskName) {
    }

    /**
     * A learner submitted an assessment pipeline for evaluation — every
     * submit, not just the first (an assessment may be re-submitted from
     * FAILED / PENDING_REEDIT, unlike a program task). For org admins and,
     * per redesign spec §2.2, the coaches who cover {@code learnerId}.
     */
    public record AssessmentSubmitted(UUID orgId, UUID learnerId, String learnerName, String pipelineName) {
    }

    /** A learner submitted an exercise sheet for review — for org admins and the coaches who cover them. */
    public record ExerciseSubmitted(UUID orgId, UUID learnerId, String learnerName, String exerciseName) {
    }

    /** A learner replied on an exercise feedback thread — for org admins and the coaches who cover them. */
    public record ExerciseFeedbackReplied(UUID orgId, UUID learnerId, String learnerName, String exerciseName) {
    }
}
