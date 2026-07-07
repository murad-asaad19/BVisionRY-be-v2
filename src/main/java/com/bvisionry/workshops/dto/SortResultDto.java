package com.bvisionry.workshops.dto;

/** Grading verdict of one sort attempt. */
public record SortResultDto(
        boolean allCorrect,
        int correctCount,
        int wrongCount,
        int total,
        int attempts,
        boolean narrowed) {
}
