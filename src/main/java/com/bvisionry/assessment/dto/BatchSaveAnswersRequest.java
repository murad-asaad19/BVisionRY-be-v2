package com.bvisionry.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BatchSaveAnswersRequest(
        @NotEmpty(message = "Answers list must not be empty")
        @Valid
        List<AnswerEntry> answers
) {
    public record AnswerEntry(
            UUID questionId,
            // Hard server-side caps, mirrored from SaveAnswerRequest: this is the
            // actual deserialized public input, so the bound must live here to stop
            // megabyte-per-answer storage + AI cost amplification on the anonymous
            // batch-save hot path. Validated because the list above is @Valid.
            @Size(max = 20_000, message = "Answer text is too long (maximum 20000 characters)")
            String responseText,
            @Size(max = 8_000, message = "Selected value is too long (maximum 8000 characters)")
            String selectedValue
    ) {}
}
