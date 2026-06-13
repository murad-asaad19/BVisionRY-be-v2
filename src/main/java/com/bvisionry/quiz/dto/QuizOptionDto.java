package com.bvisionry.quiz.dto;

import java.util.UUID;

/**
 * Option DTO used in authoring responses (includes isCorrect).
 */
public record QuizOptionDto(
        UUID id,
        String text,
        boolean isCorrect,
        int sequence
) {}
