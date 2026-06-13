package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Graded result returned after submitting a quiz attempt.
 */
public record QuizAttemptResultDto(
        UUID attemptId,
        int scorePct,
        boolean passed,
        int attemptsUsed,
        List<QuestionResult> questionResults
) {

    /**
     * Per-question correctness in the submitted attempt.
     */
    public record QuestionResult(
            UUID questionId,
            boolean correct
    ) {}
}
