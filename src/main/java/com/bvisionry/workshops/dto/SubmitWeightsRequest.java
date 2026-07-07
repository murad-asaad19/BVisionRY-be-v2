package com.bvisionry.workshops.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/** 0–100 impact score per card id. */
public record SubmitWeightsRequest(
        @NotNull Map<String, Integer> weights) {
}
