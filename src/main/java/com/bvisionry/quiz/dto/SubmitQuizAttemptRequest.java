package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for submitting a quiz attempt.
 * Each answer entry identifies which option(s) were selected for a question.
 */
public record SubmitQuizAttemptRequest(
        @NotNull List<QuestionAnswer> answers
) {

    /**
     * Selected option IDs for a single question.
     * Multiple entries for the same questionId are used for MULTI_CHOICE.
     */
    public record QuestionAnswer(
            @NotNull UUID questionId,
            @NotNull UUID optionId
    ) {}
}
