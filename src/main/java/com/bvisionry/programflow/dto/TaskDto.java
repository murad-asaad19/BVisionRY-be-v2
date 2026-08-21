package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;

/** A task card with its full form definition (admin board / builder drawer). */
public record TaskDto(
        UUID id,
        String name,
        LocalDate dueDate,
        ProgramTaskStatus status,
        boolean aiDraft,
        int position,
        ProgramTaskType taskType,
        UUID refId,
        MilestoneRole milestoneRole,
        /** Distance pillar ids this task grows (spec §1); empty when untagged. */
        List<UUID> pillarIds,
        List<FieldDto> fields) {
}
