package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.ModuleLockMode;

/** A board column: drip-scheduled module with its tasks and audience. */
public record ModuleDto(
        UUID id,
        String name,
        String summary,
        /** The module's pillar/area chip (free label this phase, spec §2.3). */
        String pillarLabel,
        /** False = always-on material; the "Week NN" kicker is suppressed. */
        boolean paced,
        int position,
        ModuleLockMode lockMode,
        OffsetDateTime unlockAt,
        AudienceDto audience,
        List<TaskDto> tasks) {
}
