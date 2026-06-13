package com.bvisionry.catalog.domain;

/**
 * Who may <em>see</em> a {@link Course} in the catalog — the value the API
 * contract exposes as {@code course.visibility}.
 *
 * <p>Distinct from the internal {@link CourseVisibility} (PUBLIC/UNLISTED/…)
 * which governs whether a course is listed at all. Stored as {@code varchar}
 * via {@code @Enumerated(STRING)} on {@code course.access}; mirrored by the
 * {@code ck_course_access} CHECK constraint.
 *
 * <ul>
 *   <li>{@link #EVERYONE} — visible to anonymous visitors.</li>
 *   <li>{@link #SIGNED_IN} — visible only to authenticated users.</li>
 *   <li>{@link #ENROLLED} — visible only to enrolled learners.</li>
 *   <li>{@link #LINK} — visible to anyone holding a direct link.</li>
 * </ul>
 */
public enum CourseAccess {
    EVERYONE,
    SIGNED_IN,
    ENROLLED,
    LINK
}
