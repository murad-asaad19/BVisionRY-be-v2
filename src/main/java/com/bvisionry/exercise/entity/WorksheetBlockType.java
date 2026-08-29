package com.bvisionry.exercise.entity;

/** The kinds of {@link WorksheetBlock}. Per-type shape lives in the block's config. */
public enum WorksheetBlockType {
    /** Author-only prose (tiptap document in {@code config.body}); collects nothing. */
    CONTENT,
    /** One free-text answer ({@code config.prompt}); answer = string. */
    TEXT,
    /**
     * A checklist ({@code config.options}: [{id, label, example}]);
     * answer = array of ticked option ids.
     */
    CHECKBOXES,
    /**
     * A mini table ({@code config.columns}: [{id, name, caption}], plus
     * {@code config.allowAddRows}); answer = array of row objects
     * (columnId → string).
     */
    TABLE
}
