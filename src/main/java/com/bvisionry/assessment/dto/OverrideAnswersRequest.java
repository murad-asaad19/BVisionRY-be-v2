package com.bvisionry.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Super-admin request to override a member's answers on a single pillar.
 * Each entry's questionId must belong to the targeted pillar; the service
 * validates the whole batch up-front so a half-applied save can't leave the
 * submission in a mixed state.
 */
public record OverrideAnswersRequest(
        @NotEmpty(message = "At least one answer must be provided")
        @Valid
        List<AnswerEntry> answers
) {
    public record AnswerEntry(
            @NotNull(message = "questionId is required")
            UUID questionId,
            String responseText,
            String selectedValue
    ) {}
}
