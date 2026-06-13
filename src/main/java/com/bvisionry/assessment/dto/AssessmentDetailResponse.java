package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AssessmentDetailResponse(
        UUID submissionId,
        UUID assignmentId,
        SubmissionStatus status,
        Instant deadline,
        PipelineInfo pipeline,
        List<PillarSection> pillars,
        /**
         * Pillars currently editable by the member because an admin has
         * unlocked them for re-edit. Empty for IN_PROGRESS / EVALUATED /
         * FAILED submissions; populated only when {@code status} is
         * {@code PENDING_REEDIT}. The frontend uses this to render unlocked
         * pillars as editable and the rest as read-only.
         */
        List<UUID> unlockedPillarIds
) {
    public record PipelineInfo(
            UUID id,
            String name,
            String description
    ) {}

    public record PillarSection(
            UUID id,
            String name,
            String description,
            String iconKey,
            int displayOrder,
            /** PERSONAL or STANDARD — the assessment flow uses this to sort the
             *  Personal pillar first and render its fields with compact inputs. */
            String type,
            List<QuestionWithAnswer> questions
    ) {}

    public record QuestionWithAnswer(
            UUID questionId,
            QuestionType type,
            String promptText,
            int displayOrder,
            boolean isRequired,
            Map<String, Object> configJson,
            AnswerData answer
    ) {}

    public record AnswerData(
            UUID answerId,
            String responseText,
            String selectedValue
    ) {}
}
