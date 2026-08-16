package com.bvisionry.exercise.entity;

/** The value shape of a sheet column. SELECT options live in {@code configJson.options}. */
public enum ExerciseColumnType {
    TEXT,
    LONG_TEXT,
    NUMBER,
    DATE,
    SELECT,
    /** Multiple free-text entries in one cell (stored as a JSON string array). */
    LIST
}
