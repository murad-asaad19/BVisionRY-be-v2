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
        /**
         * The module's 1-based position counting ONLY paced modules, so the
         * kicker reads "Week 03" for the third week however many always-on
         * sections sit between them. 0 when the module itself is unpaced and
         * has no stage number to show.
         */
        int stageNumber,
        String stageLabel,
        int dueSoonDays,
        List<FieldDto> fields,
        Map<String, Object> answers,
        SubmissionStatus status,
        OffsetDateTime savedAt,
        OffsetDateTime submittedAt,
        boolean readOnly) {
}
