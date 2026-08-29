package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.WorksheetBlock;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record UpsertExerciseTemplateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be 255 characters or less")
        String name,

        /** Sheet or worksheet. Read on create only (defaults SHEET); immutable after. */
        ExerciseTemplateKind kind,

        /** WORKSHEET only: the full ordered block list (replace-all on update). */
        List<WorksheetBlock> blocks,

        /** Serialised tiptap document — the brief shown above the member's sheet. */
        String description,

        /** `minio://bucket/key` marker or external URL for the cover art. */
        @Size(max = 500, message = "Cover image URL must be 500 characters or less")
        String coverImageUrl,

        /** Staff-only brief for the AI — what this exercise is for. Never shown to members. */
        String aiContext,

        /** Optional read-only sample row (columnId → value) shown above the sheet. */
        Map<String, Object> exampleRow,

        /** Rows seeded into every new member submission (columnId → value each). */
        List<Map<String, Object>> starterRows,

        /** False = the sheet is fixed to the starter rows. Defaults to true. */
        Boolean allowAddRows
) {
    public UpsertExerciseTemplateRequest {
        allowAddRows = allowAddRows == null || allowAddRows;
        kind = kind == null ? ExerciseTemplateKind.SHEET : kind;
    }
}
