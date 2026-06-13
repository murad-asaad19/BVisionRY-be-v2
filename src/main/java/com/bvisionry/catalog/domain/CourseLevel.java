package com.bvisionry.catalog.domain;

/**
 * Difficulty level of a {@link Course}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; the allowed set
 * is mirrored by the {@code ck_course_level} CHECK constraint in
 * {@code V76__catalog_schema.sql}.
 */
public enum CourseLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
    EXPERT,
    ALL_LEVELS
}
