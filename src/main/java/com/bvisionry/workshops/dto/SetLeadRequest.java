package com.bvisionry.workshops.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record SetLeadRequest(
        @NotNull UUID userId) {
}
