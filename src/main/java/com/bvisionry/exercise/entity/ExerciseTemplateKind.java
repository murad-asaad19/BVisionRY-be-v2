package com.bvisionry.exercise.entity;

/**
 * What shape of exercise a template is. Chosen at creation and immutable:
 * every downstream structure (columns vs blocks, rows vs answers) hangs off
 * it, so flipping it would orphan whatever members already filled.
 */
public enum ExerciseTemplateKind {
    /** The original sheet: typed columns, member-added rows. */
    SHEET,
    /** An ordered list of {@link WorksheetBlock}s the member fills top to bottom. */
    WORKSHEET
}
