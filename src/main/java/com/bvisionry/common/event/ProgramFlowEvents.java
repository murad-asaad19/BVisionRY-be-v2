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
 */
public final class ProgramFlowEvents {

    private ProgramFlowEvents() {
    }

    /** Learners were enrolled into a cohort (roster edit or org import). */
    public record CohortEnrolled(UUID orgId, String cohortName, List<UUID> userIds) {
    }

    /** A module's audience now includes these enrolled learners (admin assignment). */
    public record ModuleAssigned(UUID orgId, String moduleName, String cohortName, List<UUID> userIds) {
    }

    /** A SCHEDULED module's unlock time passed for these enrolled learners. */
    public record ModuleUnlocked(String moduleName, List<UUID> userIds) {
    }

    /** A live task's due date is inside the cohort's due-soon window for these learners. */
    public record TaskDueSoon(UUID taskId, String taskName, LocalDate dueDate, List<UUID> userIds) {
    }

    /** A learner submitted a program task (first submit only) — for org admins. */
    public record TaskSubmitted(UUID orgId, String learnerName, String taskName) {
    }
}
