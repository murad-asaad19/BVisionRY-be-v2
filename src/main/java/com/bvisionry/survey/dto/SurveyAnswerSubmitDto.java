package com.bvisionry.survey.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SurveyAnswerSubmitDto(
        @NotNull(message = "questionId is required")
        UUID questionId,

        // Hard server-side ceiling, independent of the per-question maxLength config
        // (which validateTextLength skips when unset) — stops an anonymous survey
        // respondent storing megabytes into the unbounded answer value columns.
        @Size(max = 20_000, message = "Answer text is too long (maximum 20000 characters)")
        String responseText,

        @Size(max = 8_000, message = "Selected value is too long (maximum 8000 characters)")
        String selectedValue,

        @Size(max = 200, message = "Too many selected values")
        List<@Size(max = 8_000, message = "Selected value is too long") String> selectedValues
) {}
