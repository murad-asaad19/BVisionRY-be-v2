package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Question DTO used in the "take quiz" response — options do NOT expose isCorrect.
 */
public record QuizQuestionTakingDto(
        UUID id,
        String type,
        String prompt,
        int points,
        int sequence,
        List<QuizOptionTakingDto> options
) {}
