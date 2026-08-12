package com.bvisionry.programflow.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The checkpoint payload: everything the board's admin endpoints can mutate
 * (update-module, audience, create/update/delete/move task, fields), in board
 * order — positions are implied by list order and re-derived on revert, so a
 * restored board never carries a gap that {@code ProgramAdminService.createTask}
 * / {@code createModule} (both size-based) would collide with.
 *
 * <p>Program settings are NOT here: the settings sheet has its own explicit
 * Save and is not part of the board's revert scope. Neither are the send-once
 * notification stamps ({@code unlock_notified_at} / {@code due_reminder_sent_at})
 * — a surviving row keeps its stamp untouched, and a row RE-CREATED by a revert
 * starts fresh, which is the honest reading of "this row was deleted".
 *
 * <p>Serialized to jsonb via Jackson; never exposed over the API.
 */
public record BoardSnapshot(List<ModuleSnap> modules) {

    public record ModuleSnap(
            UUID id,
            String name,
            String summary,
            String pillarLabel,
            ModuleLockMode lockMode,
            OffsetDateTime unlockAt,
            AudienceMode assignMode,
            List<UUID> memberIds,
            List<TaskSnap> tasks) {
    }

    public record TaskSnap(
            UUID id,
            String name,
            ProgramTaskType taskType,
            UUID refId,
            MilestoneRole milestoneRole,
            LocalDate dueDate,
            ProgramTaskStatus status,
            boolean aiDraft,
            List<FieldSnap> fields) {
    }

    public record FieldSnap(
            UUID id,
            FieldType type,
            boolean required,
            Map<String, Object> config) {
    }

    /** Every task id the snapshot holds — the "keep" set a revert reconciles against. */
    public List<UUID> taskIds() {
        return modules.stream().flatMap(m -> m.tasks().stream()).map(TaskSnap::id).toList();
    }

    /** Every field id the snapshot holds. */
    public List<UUID> fieldIds() {
        return modules.stream()
                .flatMap(m -> m.tasks().stream())
                .flatMap(t -> t.fields().stream())
                .map(FieldSnap::id)
                .toList();
    }

    public List<UUID> moduleIds() {
        return modules.stream().map(ModuleSnap::id).toList();
    }
}
