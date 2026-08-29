package com.bvisionry.exercise.entity;

import java.util.Map;
import java.util.UUID;

/**
 * One block of a WORKSHEET template, stored inside the template's
 * {@code blocks} jsonb document (a value object, not an entity — blocks have
 * no table). The id is client-minted at authoring time and immutable once the
 * template is assigned: member answers and comment anchors key on it.
 *
 * <p>{@code config} is the per-type payload — see {@link WorksheetBlockType}
 * for each type's expected keys. Kept loose (like a sheet column's
 * {@code configJson}) so block types can grow fields without a schema change.
 */
public record WorksheetBlock(
        UUID id,
        WorksheetBlockType type,
        /** Section heading shown above the block; optional for CONTENT. */
        String label,
        /** Whether submit requires an answer. Meaningless on CONTENT. */
        boolean required,
        Map<String, Object> config
) {}
