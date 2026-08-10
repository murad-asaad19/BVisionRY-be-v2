package com.bvisionry.programflow.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The billing family's launch history + quota grants (§7b: the ledger is the
 * audit trail of the paid act). {@code cohortName} is null when the cohort was
 * deleted since — the ledger row deliberately outlives it; actor names are
 * null for system/deleted actors.
 */
public record LaunchLedgerResponse(
        List<LaunchRow> launches,
        List<GrantRow> grants) {

    public record LaunchRow(
            UUID id,
            UUID cohortId,
            String cohortName,
            Instant launchedAt,
            String launchedByName) {
    }

    public record GrantRow(
            UUID id,
            Instant createdAt,
            String grantedByName,
            String note) {
    }
}
