package com.bvisionry.pipeline;

/**
 * Identifiers for system-managed questions inside the Personal pillar.
 * These questions are auto-created with every pipeline and cannot be deleted
 * or restructured by admins so the AI always has the data it needs (first
 * name, last name, gender) to address the assessed person correctly.
 */
public final class SystemQuestion {
    public static final String FIRST_NAME = "FIRST_NAME";
    public static final String LAST_NAME = "LAST_NAME";
    public static final String GENDER = "GENDER";

    private SystemQuestion() {}
}
