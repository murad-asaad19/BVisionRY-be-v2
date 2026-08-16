package com.bvisionry.comparison.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The pillar pair mapping of one (baseline, distance) pipeline pair, plus the
 * pillar option lists the remap dialog needs. Served read-only to org admins
 * for their cohort's designated pair; editable by SUPER_ADMIN only.
 */
public record PillarMappingResponse(
        UUID baselinePipelineId,
        String baselinePipelineName,
        UUID distancePipelineId,
        String distancePipelineName,
        List<MappingRow> mappings,
        List<PillarOption> baselinePillars,
        List<PillarOption> distancePillars) {

    /** {@code source} is AUTO or MANUAL; a null side means newly measured / not re-measured. */
    public record MappingRow(
            UUID id,
            UUID baselinePillarId,
            String baselinePillarName,
            UUID distancePillarId,
            String distancePillarName,
            String source,
            Instant updatedAt) {
    }

    public record PillarOption(UUID id, String name) {
    }
}
