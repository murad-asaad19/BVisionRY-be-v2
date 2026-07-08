package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Per-org program tweakables (also the PUT settings request body). */
public record ProgramSettingsDto(
        @NotBlank @Size(max = 30) String stageLabel,
        boolean dripEnabled,
        @Min(1) @Max(10) int dueSoonDays,
        @Size(max = 120) String endLabel,
        OffsetDateTime endAt) {

    public static ProgramSettingsDto defaults() {
        return new ProgramSettingsDto("Week", true, 3, null, null);
    }
}
