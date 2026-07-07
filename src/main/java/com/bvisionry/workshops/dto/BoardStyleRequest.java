package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** One of the frontend's live-board style keys — lowercase word, e.g. 'lanes'. */
public record BoardStyleRequest(
        @NotBlank @Pattern(regexp = "[a-z]{2,24}") String style) {
}
