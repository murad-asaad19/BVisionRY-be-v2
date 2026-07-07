package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;

public record RespondRequest(
        @NotBlank String cardId,
        @NotBlank String text) {
}
