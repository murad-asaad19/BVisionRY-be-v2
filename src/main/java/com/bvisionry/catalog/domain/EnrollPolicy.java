package com.bvisionry.catalog.domain;

/**
 * How learners may enroll in a {@link Course}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_course_enroll_policy} CHECK constraint in {@code V76__catalog_schema.sql}.
 */
public enum EnrollPolicy {
    OPEN,
    INVITATION,
    REQUEST,
    PAYMENT,
    MANUAL
}
