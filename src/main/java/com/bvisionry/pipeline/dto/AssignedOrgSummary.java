package com.bvisionry.pipeline.dto;

import java.util.UUID;

/**
 * Per-row entry for {@link PipelineSummaryResponse#assignedOrganizations()}.
 * One per (pipeline, organization) pair the pipeline is currently assigned
 * to (either manually, via auto-assign, or both).
 *
 * <p>{@code autoAssign} is true when an auto-assign rule exists for this
 * (pipeline, org) pair — even if no individual assignments have been
 * materialised yet (e.g. a rule set up against an empty org). The frontend
 * uses this to badge the org pill in the pipeline list.
 */
public record AssignedOrgSummary(
        UUID id,
        String name,
        boolean autoAssign
) {}
