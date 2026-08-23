package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;
import com.bvisionry.programflow.dto.JourneyTaskState;

/** Pure domain rules shared by the admin and learner services. */
final class ProgramRules {

    private ProgramRules() {
    }

    /** Does this module's audience include the given member? */
    static boolean includes(ProgramModule m, UUID userId) {
        return switch (m.getAssignMode()) {
            case ALL -> true;
            case MEMBERS -> m.getMemberIds().contains(userId);
        };
    }

    /**
     * Drip state of {@code modules.get(index)} for one learner. The first
     * visible module is never sequential-locked; a previous module with no
     * live tasks counts as done (there is nothing to complete in it).
     *
     * @param modules       the learner's visible modules, in board order
     * @param submittedTaskIds live task ids that release the drip — every
     *        state {@link #satisfiesDrip} accepts, not only completed work
     */
    static LockState lockState(List<ProgramModule> modules, int index, boolean dripEnabled,
            Set<UUID> submittedTaskIds, Set<UUID> blockedCourseIds, OffsetDateTime now) {
        ProgramModule m = modules.get(index);
        if (!dripEnabled || m.getLockMode() == ModuleLockMode.UNLOCKED) {
            return LockState.UNLOCKED;
        }
        if (m.getLockMode() == ModuleLockMode.SCHEDULED) {
            return m.getUnlockAt() == null || !now.isBefore(m.getUnlockAt())
                    ? LockState.UNLOCKED
                    : LockState.LOCKED_SCHEDULED;
        }
        // SEQUENTIAL
        if (index == 0) {
            return LockState.UNLOCKED;
        }
        boolean previousDone = liveTasks(modules.get(index - 1)).stream()
                .filter(t -> gates(t, blockedCourseIds))
                .allMatch(t -> submittedTaskIds.contains(t.getId()));
        return previousDone ? LockState.UNLOCKED : LockState.LOCKED_SEQUENTIAL;
    }

    /**
     * Does this task hold the chain — i.e. count in every completion denominator
     * (journey progress, pulse, drip, the continue cursor, the matrix's overdue
     * flag)?
     *
     * <p>THE one source of truth for that question, so a member can never be
     * stuck behind work they are not able to do:
     * <ul>
     *   <li>a type they cannot complete in-app (none today) never gated;</li>
     *   <li>a COURSE whose course the member's org can no longer SEE cannot be
     *       opened at all (spec §3 downgrade policy blocks new content), so it
     *       must not gate either — otherwise narrowing a course's visibility
     *       mid-cohort deadlocks every founder behind that module.</li>
     * </ul>
     * The row still RENDERS, flagged unavailable; it just stops being a gate.
     *
     * <p>SQL twin: {@code TaskCompletion.COUNTS_FOR_USER} — every SQL
     * completion fraction applies it (change one, change the other;
     * {@code TaskSpineIntegrationTest} asserts they agree).
     *
     * @param blockedCourseIds course ids invisible to the cohort's org, from
     *        {@code CourseVisibilityAccess#invisibleCourseIds}. Empty = nothing blocked.
     */
    static boolean gates(com.bvisionry.programflow.domain.ProgramTask t, Set<UUID> blockedCourseIds) {
        if (!t.getTaskType().completableInApp()) {
            return false;
        }
        return !(t.getTaskType() == ProgramTaskType.COURSE
                && t.getRefId() != null && blockedCourseIds.contains(t.getRefId()));
    }

    static List<com.bvisionry.programflow.domain.ProgramTask> liveTasks(ProgramModule m) {
        return m.getTasks().stream()
                .filter(t -> t.getStatus() == com.bvisionry.programflow.domain.ProgramTaskStatus.LIVE)
                .toList();
    }

    /** Mirrors the player's per-type "has the learner actually answered" rule. */
    @SuppressWarnings("unchecked")
    static boolean isAnswered(ProgramTaskField f, Object v) {
        if (v == null) {
            return false;
        }
        return switch (f.getFieldType()) {
            case MCQ -> {
                boolean multi = Boolean.TRUE.equals(f.getConfig().get("multi"));
                yield !multi || (v instanceof Collection<?> c && !c.isEmpty());
            }
            case CHECKLIST -> {
                Object items = f.getConfig().get("items");
                int total = items instanceof Collection<?> c ? c.size() : 0;
                yield v instanceof Collection<?> c && total > 0 && c.size() == total;
            }
            case RATING -> v instanceof Number n && n.intValue() > 0;
            case FILE -> !(v instanceof Map<?, ?> m2 && m2.isEmpty()) && !String.valueOf(v).isBlank();
            case SHORT, LONG -> !String.valueOf(v).trim().isEmpty();
            case INSTRUCTIONS, VIDEO -> true;
        };
    }

    /** Required answerable fields with no valid answer. */
    static List<UUID> missingRequired(List<ProgramTaskField> fields, Map<String, Object> answers) {
        return fields.stream()
                .filter(f -> f.getFieldType().answerable() && f.isRequired())
                .filter(f -> !isAnswered(f, answers.get(f.getId().toString())))
                .map(ProgramTaskField::getId)
                .toList();
    }

    /* ------------------------------------------- typed task spine (spec §1) */

    /** LESSON: my program-submission status → unified vocabulary. */
    static JourneyTaskState lessonState(com.bvisionry.programflow.domain.SubmissionStatus status) {
        if (status == null) {
            return JourneyTaskState.NOT_STARTED;
        }
        return status == com.bvisionry.programflow.domain.SubmissionStatus.SUBMITTED
                ? JourneyTaskState.DONE
                : JourneyTaskState.IN_PROGRESS;
    }

    /** COURSE: enrollment status → unified vocabulary (null row = not started). */
    static JourneyTaskState courseState(String enrollmentStatus) {
        if (enrollmentStatus == null) {
            return JourneyTaskState.NOT_STARTED;
        }
        return "COMPLETED".equals(enrollmentStatus)
                ? JourneyTaskState.DONE
                : JourneyTaskState.IN_PROGRESS;
    }

    /** EXERCISE: exercise-submission status (+reviewedAt implied by REVIEWED) → unified vocabulary. */
    static JourneyTaskState exerciseState(String submissionStatus) {
        if (submissionStatus == null) {
            return JourneyTaskState.NOT_STARTED;
        }
        return switch (submissionStatus) {
            case "SUBMITTED" -> JourneyTaskState.SUBMITTED;
            case "CHANGES_REQUESTED" -> JourneyTaskState.CHANGES_REQUESTED;
            case "REVIEWED" -> JourneyTaskState.REVIEWED;
            case "NOT_SUBMITTED" -> JourneyTaskState.NOT_SUBMITTED;
            default -> JourneyTaskState.IN_PROGRESS;
        };
    }

    /**
     * ASSESSMENT: the tagged submission's status → unified vocabulary.
     * FAILED / NEEDS_REVIEW / PENDING_REEDIT collapse to SUBMITTED — the
     * member has done their part; evaluation state is an admin concern.
     */
    static JourneyTaskState assessmentState(String submissionStatus) {
        if (submissionStatus == null) {
            return JourneyTaskState.NOT_STARTED;
        }
        return switch (submissionStatus) {
            case "IN_PROGRESS" -> JourneyTaskState.IN_PROGRESS;
            case "EVALUATED" -> JourneyTaskState.EVALUATED;
            default -> JourneyTaskState.SUBMITTED;
        };
    }

    /**
     * Does this state count toward completion/progress (the engagement
     * record's done fraction, participation, journey x/y)? The member's side
     * of the work is done and stands: submitted, reviewed or evaluated. A
     * returned CHANGES_REQUESTED copy is back with the member, and a
     * NOT_SUBMITTED record (V208) is closed missing work — neither counts,
     * or the completion and participation numbers would inflate. Both still
     * release the drip lock — see {@link #satisfiesDrip}.
     */
    static boolean done(JourneyTaskState state) {
        return state == JourneyTaskState.SUBMITTED
                || state == JourneyTaskState.REVIEWED
                || state == JourneyTaskState.EVALUATED
                || state == JourneyTaskState.DONE;
    }

    /**
     * Does this state release the sequential drip lock and the continue
     * cursor (operator decision 2026-08-23)? Everything {@link #done} plus
     * the two states that must not freeze the journey even though they never
     * count as completed: CHANGES_REQUESTED (handed in once — the review
     * loop is open feedback, not a lock) and NOT_SUBMITTED (the operator
     * closed the record, V208, so the member navigates on past it). Only
     * NOT_STARTED and IN_PROGRESS hold the chain.
     */
    static boolean satisfiesDrip(JourneyTaskState state) {
        return state != JourneyTaskState.NOT_STARTED
                && state != JourneyTaskState.IN_PROGRESS;
    }

    /**
     * Structural rules of the typed spine a single task must satisfy,
     * expressed as per-field errors (empty map = valid). Cohort-level rules
     * (milestone uniqueness, designated-pipeline sync) need data and live in
     * {@code ProgramAdminService}.
     */
    static Map<String, String> taskTypeFieldErrors(ProgramTaskType type, UUID refId,
            MilestoneRole milestoneRole, ProgramTaskStatus status, int fieldCount) {
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        if (type == ProgramTaskType.LESSON) {
            if (refId != null) {
                errors.put("refId", "Lesson tasks do not reference another object.");
            }
        } else {
            if (fieldCount > 0) {
                errors.put("fields", "Only lesson tasks have form fields.");
            }
            if (status == ProgramTaskStatus.LIVE && refId == null) {
                errors.put("refId", "Pick what this task references before publishing it.");
            }
        }
        if (type == ProgramTaskType.ASSESSMENT) {
            if (milestoneRole == null) {
                errors.put("milestoneRole",
                        "Assessment tasks need a milestone role (baseline, check-in or distance).");
            }
        } else if (milestoneRole != null) {
            errors.put("milestoneRole", "Only assessment tasks carry a milestone role.");
        }
        return errors;
    }

    /**
     * Consecutive-day submission streak ending today (or yesterday, so the
     * streak survives until the end of the current day).
     */
    static int streak(Collection<OffsetDateTime> submissionTimes, LocalDate today) {
        Set<LocalDate> days = new HashSet<>();
        for (OffsetDateTime t : submissionTimes) {
            if (t != null) {
                // Bucket by the same zone `today` is computed in (the JVM zone the
                // rest of the program uses via LocalDate.now()/OffsetDateTime.now()),
                // so a submission's calendar day lines up with the streak cursor.
                days.add(t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate());
            }
        }
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
