package com.bvisionry.programflow.domain;

import java.util.Set;

/**
 * The eight field types a {@link ProgramTask} form is built from. Per-type
 * shape lives in {@link ProgramTaskField#getConfig()} (JSONB).
 */
public enum FieldType {
    INSTRUCTIONS,
    VIDEO,
    MCQ,
    SHORT,
    LONG,
    FILE,
    CHECKLIST,
    RATING;

    /** Types that collect an answer (everything except read-only content). */
    public static final Set<FieldType> ANSWERABLE = Set.of(MCQ, SHORT, LONG, FILE, CHECKLIST, RATING);

    public boolean answerable() {
        return ANSWERABLE.contains(this);
    }
}
