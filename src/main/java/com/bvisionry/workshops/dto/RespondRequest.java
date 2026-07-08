package com.bvisionry.workshops.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** A QUESTION submission: one answer per shared top card — all cards required. */
public record RespondRequest(
        @NotEmpty List<@Valid Answer> answers) {

    public record Answer(
            @NotBlank String cardId,
            @NotBlank String text) {
    }
}
