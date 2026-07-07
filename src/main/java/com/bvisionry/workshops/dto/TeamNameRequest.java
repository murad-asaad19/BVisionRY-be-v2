package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamNameRequest(
        @NotBlank @Size(max = 120) String name) {
}
