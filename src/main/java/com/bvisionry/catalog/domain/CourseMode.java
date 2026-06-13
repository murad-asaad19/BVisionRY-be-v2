package com.bvisionry.catalog.domain;

/**
 * Delivery mode of a {@link Course}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_course_mode} CHECK constraint in {@code V76__catalog_schema.sql}.
 */
public enum CourseMode {
    SELF_PACED,
    INSTRUCTOR_LED,
    BLENDED,
    COHORT,
    LIVE
}
