package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.ProgramTaskStatus;

/** A task card with its full form definition (admin board / builder drawer). */
public record TaskDto(
        UUID id,
        String name,
        LocalDate dueDate,
        ProgramTaskStatus status,
        boolean aiDraft,
        int position,
        List<FieldDto> fields) {
}
