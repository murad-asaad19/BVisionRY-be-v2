package com.bvisionry.programflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.MilestoneRole;

/**
 * The cohort board's Founders tab (spec §2.3): rows = founders, columns =
 * modules (ordered) + check-in milestone columns; cells = per-founder
 * done/total per module and per-milestone state; row end = FRI + Δ, open
 * items, last seen; plus the needs-attention strip inputs per row.
 *
 * @param pillarThreshold the platform `attention.pillar_threshold` in effect
 *                        (§11: bands/thresholds always display the configured
 *                        value, never hardcoded copy)
 */
public record CohortMatrixResponse(
        List<ModuleColumn> moduleColumns,
        List<MilestoneColumn> milestoneColumns,
        List<FounderRow> rows,
        int pillarThreshold) {

    /** A module column; cells under it aggregate its LIVE completable tasks. */
    public record ModuleColumn(UUID moduleId, String name, String pillarLabel, int position) {
    }

    /** An ASSESSMENT milestone task column (BASELINE / CHECKIN / DISTANCE), board order. */
    public record MilestoneColumn(UUID taskId, String name, MilestoneRole role, LocalDate dueDate) {
    }

    public record FounderRow(
            UUID userId,
            String name,
            List<ModuleCell> moduleCells,
            List<MilestoneCell> milestoneCells,
            BigDecimal friLatest,
            BigDecimal friDelta,
            /** Exercise submissions awaiting review — the "open items" count. */
            int awaitingReview,
            OffsetDateTime lastSeenAt,
            /** Why this founder is on the needs-attention strip; empty = fine. */
            List<AttentionFlag> attentionFlags) {
    }

    /**
     * Done/total over the module's LIVE completable tasks the founder is in
     * the audience of. {@code assigned=false} = the audience excludes them
     * (cell renders em-dash, counts nowhere).
     */
    public record ModuleCell(boolean assigned, int done, int total) {
    }

    /** One milestone's member state (+score once evaluated) with §7b stamps. */
    public record MilestoneCell(
            boolean assigned,
            JourneyTaskState state,
            BigDecimal score,
            Instant submittedAt,
            Instant evaluatedAt) {
    }

    /** The §2.3 needs-attention strip vocabulary. */
    public enum AttentionFlag {
        /** No member activity for over 7 days (or never). */
        IDLE,
        /** At least one LIVE, in-audience, completable task past its due date and not done. */
        OVERDUE_TASKS,
        /**
         * The cohort's BASELINE milestone isn't done although the founder has
         * already done work in a LATER module — they moved past the check-in.
         */
        CHECKIN_UNSTARTED,
        /** Latest evaluated assessment has a pillar under the platform threshold. */
        PILLAR_BELOW_THRESHOLD
    }
}
