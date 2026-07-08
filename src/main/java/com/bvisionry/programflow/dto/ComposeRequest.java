package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The director's brief for the AI module composer. */
public record ComposeRequest(@NotBlank @Size(max = 2000) String prompt) {
}
