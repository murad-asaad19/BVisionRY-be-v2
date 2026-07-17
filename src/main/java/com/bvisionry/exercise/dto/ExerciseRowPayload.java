package com.bvisionry.exercise.dto;

import java.util.Map;
import java.util.UUID;

/**
 * One row in a save request. {@code id} null = new row; an existing id updates
 * that row in place (preserving its comment anchors). Rows owned by the
 * submission but absent from the request are soft-deleted.
 */
public record ExerciseRowPayload(
        UUID id,
        Map<String, Object> cells
) {}
