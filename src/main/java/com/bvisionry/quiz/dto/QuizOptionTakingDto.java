package com.bvisionry.quiz.dto;

import java.util.UUID;

/**
 * Option DTO used in the "take quiz" response — isCorrect is intentionally omitted.
 */
public record QuizOptionTakingDto(
        UUID id,
        String text,
        int sequence
) {}
