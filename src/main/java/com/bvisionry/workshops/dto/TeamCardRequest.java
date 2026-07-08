package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** One of the frontend's team-card keys — lowercase word, e.g. 'red', 'gold'. */
public record TeamCardRequest(
        @NotBlank @Pattern(regexp = "[a-z]{2,24}") String card) {
}
