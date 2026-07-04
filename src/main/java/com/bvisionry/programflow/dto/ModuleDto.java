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
        int position,
        ModuleLockMode lockMode,
        OffsetDateTime unlockAt,
        AudienceDto audience,
        List<TaskDto> tasks) {
}
