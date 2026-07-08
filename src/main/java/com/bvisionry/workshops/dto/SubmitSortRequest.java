package com.bvisionry.workshops.dto;

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

/** The player's pile choice per card id: {@code "left"} or {@code "right"}. */
public record SubmitSortRequest(
        @NotEmpty Map<String, String> sorted) {
}
