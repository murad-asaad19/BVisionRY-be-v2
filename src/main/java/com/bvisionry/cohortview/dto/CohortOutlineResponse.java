package com.bvisionry.cohortview.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The cohort's curriculum as an ORG ADMIN may read it (the dedicated cohort
 * view's "cohort outline"): modules in board order, each with the LIVE tasks it
 * contains and the org slice's completion count.
 *
 * <p>This is deliberately NOT the builder's board ({@code ProgramAdminController},
 * SUPER_ADMIN only): no fields, no drafts, no version, nothing writable. An org
 * admin running the cohort needs to see what the programme asks of their
 * founders and how far the group has got — not to author it.
 */
public record CohortOutlineResponse(
        UUID cohortId,
        String name,
        String status,
        /** Cohort-wide: LIVE tasks × the org's members, and how many are done. */
        int taskCount,
        List<OutlineModule> modules) {

    public record OutlineModule(
            UUID moduleId,
            String name,
            String summary,
            /** The module's pillar/area chip (spec §2.3). */
            String pillarLabel,
            int position,
            /** UNLOCKED | SCHEDULED | SEQUENTIAL — how the module opens. */
            String lockMode,
            /** SCHEDULED only: when it opens. */
            Instant unlockAt,
            /** ALL | MEMBERS — whether the module is narrowed to named members. */
            String audienceMode,
            /** Members of THIS org the module reaches. */
            int audienceCount,
            List<OutlineTask> tasks) {}

    public record OutlineTask(
            UUID taskId,
            String name,
            /** LESSON | COURSE | EXERCISE | ASSESSMENT | SURVEY. */
            String taskType,
            /** BASELINE | CHECKIN | DISTANCE on milestone assessments; null otherwise. */
            String milestoneRole,
            LocalDate dueDate,
            int position,
            /**
             * The org slice's progress on this task: how many of the members the
             * module reaches have done it, out of how many it reaches. Same
             * done-authority as every other completion surface
             * ({@code TaskCompletion.DONE_FOR_USER}).
             */
            int doneCount,
            int totalMembers) {}
}
