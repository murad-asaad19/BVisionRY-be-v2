package com.bvisionry.programflow.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A cohort's whole curriculum as one value — modules, audience, tasks, fields —
 * in board order, with positions implied by list order and re-derived on write.
 * This is what {@code BoardRestoreRepository} reconciles the DB against, and
 * what {@code SaveBoardRequest} is translated into.
 *
 * <p>Program settings are NOT here: the settings sheet has its own explicit
 * Save and its own scope. Neither are the send-once notification stamps
 * ({@code unlock_notified_at} / {@code due_reminder_sent_at}) — a row that
 * survives a save keeps its stamp untouched, and one the save re-creates under a
 * remembered id starts fresh, which is the honest reading of "this was deleted".
 */
public record BoardSnapshot(List<ModuleSnap> modules) {

    public record ModuleSnap(
            UUID id,
            String name,
            String summary,
            String pillarLabel,
            boolean paced,
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
