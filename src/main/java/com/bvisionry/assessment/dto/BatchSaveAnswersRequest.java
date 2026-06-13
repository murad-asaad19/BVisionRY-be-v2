package com.bvisionry.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record BatchSaveAnswersRequest(
        @NotEmpty(message = "Answers list must not be empty")
        @Valid
        List<AnswerEntry> answers
) {
    public record AnswerEntry(
            UUID questionId,
            String responseText,
            String selectedValue
    ) {}
}
