package com.bvisionry.catalog.domain;

/**
 * Catalog visibility of a {@link Course}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_course_visibility} CHECK constraint in {@code V76__catalog_schema.sql}.
 *
 * <ul>
 *   <li>{@link #PUBLIC} — listed in the public catalog.</li>
 *   <li>{@link #UNLISTED} — reachable by direct link, not listed.</li>
 *   <li>{@link #PRIVATE} — not reachable without explicit access.</li>
 *   <li>{@link #MEMBERS} — visible only to members of the org.</li>
 * </ul>
 */
public enum CourseVisibility {
    PUBLIC,
    UNLISTED,
    PRIVATE,
    MEMBERS
}
