package com.bvisionry.catalog.domain;

/**
 * Publish-lifecycle state of a {@link Course} (draft → published → archived,
 * with re-publish).
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_course_state} CHECK constraint in {@code V76__catalog_schema.sql}.
 */
public enum CourseState {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
