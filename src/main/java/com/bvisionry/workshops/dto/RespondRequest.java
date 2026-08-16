package com.bvisionry.workshops.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A QUESTION submission: one answer per shared top card — the service rejects
 * a missing answer per card. An EMPTY list is legal and means the question has
 * no shared cards to answer.
 */
public record RespondRequest(
        @NotNull List<@Valid Answer> answers) {

    public record Answer(
            @NotBlank String cardId,
            @NotBlank String text) {
    }
}
