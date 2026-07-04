package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create / rename team body. */
public record TeamNameRequest(@NotBlank @Size(max = 120) String name) {
}
