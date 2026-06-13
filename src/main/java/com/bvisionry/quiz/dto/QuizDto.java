package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Full quiz DTO for authoring (includes correct answers in options).
 */
public record QuizDto(
        UUID id,
        UUID contentId,
        int passingScorePct,
        int maxAttempts,
        boolean shuffle,
        List<QuizQuestionDto> questions
) {}
