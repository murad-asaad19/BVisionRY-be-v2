package com.bvisionry.exercise.entity;

/** The value shape of a sheet column. SELECT options live in {@code configJson.options}. */
public enum ExerciseColumnType {
    TEXT,
    LONG_TEXT,
    NUMBER,
    DATE,
    SELECT,
    /** Multiple free-text entries in one cell (stored as a JSON string array). */
    LIST;

    /**
     * May a column that members have already filled change from this type to
     * {@code target} without invalidating a single stored cell?
     *
     * <p>Cells are untyped JSON ({@code exercise_rows.cells}), so a type change
     * rewrites nothing and can never delete a value. The only question is
     * whether every stored value still READS correctly afterwards.
     *
     * <p>TEXT and LONG_TEXT both store a plain string, so they are
     * interchangeable in either direction. Either may also widen to LIST: the
     * web's {@code listEntries} reads a bare string as the list's first entry,
     * so the member's text stays visible and becomes a real array the first
     * time they edit that cell.
     *
     * <p>Everything else stays frozen once assigned. NUMBER and DATE would
     * render existing prose as a blank input; SELECT would show nothing for any
     * value matching no option; and LIST may not narrow back, because a
     * multi-entry cell has no single value to collapse into and review comments
     * anchored to an entry id would lose their target.
     */
    public boolean convertsLosslesslyTo(ExerciseColumnType target) {
        if (this == target) {
            return true;
        }
        return (this == TEXT || this == LONG_TEXT)
                && (target == TEXT || target == LONG_TEXT || target == LIST);
    }
}
