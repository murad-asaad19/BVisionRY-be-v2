package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** One of the frontend's 10 team-card keys — lowercase word, e.g. 'red', 'indigo'. */
public record TeamCardRequest(
        @NotBlank @Pattern(regexp = "[a-z]{2,24}") String card) {
}
