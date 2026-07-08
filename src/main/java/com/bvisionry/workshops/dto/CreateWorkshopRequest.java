package com.bvisionry.workshops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkshopRequest(
        @NotBlank @Size(max = 200) String name) {
}
