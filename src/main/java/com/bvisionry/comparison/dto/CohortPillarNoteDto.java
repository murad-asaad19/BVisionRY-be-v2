package com.bvisionry.comparison.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * The coach note on one pillar of the cohort Growth tab (V214), and the write
 * that sets it. A blank {@code note} CLEARS it — there is no separate delete,
 * because "no note" is the ordinary state, not a removal anyone audits.
 */
public record CohortPillarNoteDto(UUID pillarId, String note, Instant updatedAt) {

    /** Two sentences, per the design; the cap stops a report being pasted in. */
    public record Request(@Size(max = 600) String note) {
    }
}
