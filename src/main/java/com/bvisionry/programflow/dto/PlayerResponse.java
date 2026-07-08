package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bvisionry.programflow.domain.SubmissionStatus;

/** Everything the stepped task player needs: form definition + my draft. */
public record PlayerResponse(
        UUID taskId,
        String taskName,
        LocalDate dueDate,
        UUID moduleId,
        String moduleName,
        int moduleIndex,
        String stageLabel,
        int dueSoonDays,
        List<FieldDto> fields,
        Map<String, Object> answers,
        SubmissionStatus status,
        OffsetDateTime savedAt,
        OffsetDateTime submittedAt,
        boolean readOnly) {
}
