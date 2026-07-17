package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** Full ordered list of the template's column ids. */
public record ReorderColumnsRequest(
        @NotEmpty(message = "columnIds is required")
        List<UUID> columnIds
) {}
