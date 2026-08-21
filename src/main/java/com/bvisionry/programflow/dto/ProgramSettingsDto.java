package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Per-cohort program tweakables (also the PUT settings request body).
 *
 * <p>{@code baselinePipelineId}/{@code distancePipelineId} are READ-ONLY here:
 * the distance pair is derived from the cohort's BASELINE and DISTANCE
 * milestone tasks (spec §5), so the PUT ignores whatever they carry and
 * re-derives from the curriculum. They stay on the response because the board
 * screen reads them (pillar mapping, pending-comparison repair).
 */
public record ProgramSettingsDto(
        @NotBlank @Size(max = 30) String stageLabel,
        boolean dripEnabled,
        @Min(1) @Max(10) int dueSoonDays,
        @Size(max = 120) String endLabel,
        OffsetDateTime endAt,
        UUID baselinePipelineId,
        UUID distancePipelineId) {

    public static ProgramSettingsDto defaults() {
        return new ProgramSettingsDto("Week", true, 3, null, null, null, null);
    }
}
