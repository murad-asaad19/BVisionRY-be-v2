package com.bvisionry.catalog.dto.authoring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST/PUT on a course section.
 */
public record UpsertSectionRequest(
        @NotBlank @Size(max = 200) String title,
        int sequence) {
}
