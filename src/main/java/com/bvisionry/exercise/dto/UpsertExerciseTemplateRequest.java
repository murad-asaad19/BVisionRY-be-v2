package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record UpsertExerciseTemplateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be 255 characters or less")
        String name,

        String description,

        /** Optional read-only sample row (columnId → value) shown above the sheet. */
        Map<String, Object> exampleRow,

        /** Rows seeded into every new member submission (columnId → value each). */
        List<Map<String, Object>> starterRows,

        /** False = the sheet is fixed to the starter rows. Defaults to true. */
        Boolean allowAddRows
) {
    public UpsertExerciseTemplateRequest {
        allowAddRows = allowAddRows == null || allowAddRows;
    }
}
