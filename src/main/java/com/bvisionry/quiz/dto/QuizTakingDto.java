package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Quiz DTO for learners — questions/options do NOT include correct-answer flags.
 */
public record QuizTakingDto(
        UUID id,
        UUID contentId,
        int passingScorePct,
        int maxAttempts,
        boolean shuffle,
        List<QuizQuestionTakingDto> questions
) {}
