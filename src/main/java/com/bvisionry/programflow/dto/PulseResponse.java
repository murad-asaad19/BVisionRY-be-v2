package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cohort pulse matrix: every member × every LIVE task. Cells align 1:1 with
 * {@code columns}. Due-soon / overdue tinting is derived client-side from the
 * column's due date, the cell state and the org's {@code dueSoonDays} threshold.
 * A member outside a module's audience gets {@link CellState#NOT_ASSIGNED} for
 * its tasks and those tasks are excluded from their completion percentage.
 */
public record PulseResponse(
        List<PulseColumn> columns,
        List<PulseRow> rows,
        int dueSoonDays) {

    public record PulseColumn(
            UUID taskId,
            int moduleIndex,
            int taskIndex,
            String moduleName,
            String taskName,
            LocalDate dueDate) {
    }

    public record PulseRow(
            UUID userId,
            String name,
            String teamName,
            List<CellState> cells,
            int completionPct) {
    }

    public enum CellState {
        SUBMITTED,
        IN_DRAFT,
        NOT_STARTED,
        NOT_ASSIGNED
    }
}
