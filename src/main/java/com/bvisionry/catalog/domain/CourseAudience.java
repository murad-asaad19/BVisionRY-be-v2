package com.bvisionry.catalog.domain;

/**
 * The audience / distribution channel a {@link Course} is published to.
 *
 * <p>This is the value the API contract exposes as {@code course.mode}
 * (distinct from the internal delivery {@link CourseMode}). Stored as
 * {@code varchar} via {@code @Enumerated(STRING)} on {@code course.audience};
 * mirrored by the {@code ck_course_audience} CHECK constraint.
 *
 * <ul>
 *   <li>{@link #EMPLOYEE} — internal employee training.</li>
 *   <li>{@link #PUBLIC} — open public marketing catalog.</li>
 *   <li>{@link #B2B} — sold/assigned to partner organisations.</li>
 * </ul>
 */
public enum CourseAudience {
    EMPLOYEE,
    PUBLIC,
    B2B
}
