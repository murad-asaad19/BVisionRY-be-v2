package com.bvisionry.programflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bvisionry.programflow.domain.ProgramTaskType;

/**
 * One row of the member's "Direct assignments" section (redesign spec §2.1):
 * work NOT attached to any cohort task — direct exercise assignments,
 * direct/auto assessment assignments, self/auto course enrollments. Same
 * status vocabulary as journey tasks; §7b timestamps throughout.
 */
public record DirectAssignmentDto(
        /** The owning slice's row: assignment id (exercise/assessment) or enrollment id (course). */
        UUID id,
        /** COURSE, EXERCISE or ASSESSMENT — lessons/workshops/surveys have no direct form. */
        ProgramTaskType taskType,
        /** Course / exercise template / pipeline id. */
        UUID refId,
        /** Open target: course id, exercise submission id, or latest assessment submission id. */
        UUID targetId,
        String title,
        JourneyTaskState state,
        /** Course progress 0–100; null for other types. */
        Integer progressPct,
        /** Evaluated assessment overall score; null otherwise. */
        BigDecimal score,
        Instant deadline,
        Instant assignedAt,
        Instant submittedAt,
        Instant completedAt,
        /**
         * COURSE only (spec §3): ORG_RULE | DIRECT | AI_SUGGESTED | SELF, the
         * STRONGEST source that put this course on the member's shelf. Null for
         * exercises and assessments, which have one path each.
         */
        String source,
        /** Spec §3: required work gates journey progress; optional is shown and never gates. */
        boolean required) {
}
