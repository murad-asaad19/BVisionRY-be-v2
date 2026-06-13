package com.bvisionry.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * Question DTO used in authoring responses (options include isCorrect).
 */
public record QuizQuestionDto(
        UUID id,
        String type,
        String prompt,
        int points,
        int sequence,
        List<QuizOptionDto> options
) {}
