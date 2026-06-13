package com.bvisionry.catalog.domain;

/**
 * Type of a {@link Content} item within a {@link Section}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_content_type} CHECK constraint in {@code V76__catalog_schema.sql}.
 * The set is the union of the original authoring types and the API contract's
 * {@code lesson.type} vocabulary so the value serialises 1:1 to the frontend.
 */
public enum ContentType {
    VIDEO,
    ARTICLE,
    QUIZ,
    DOCUMENT,
    SCORM,
    ASSIGNMENT,
    WEBPAGE,
    // API-contract lesson types (frontend `lesson.type`):
    PDF,
    CERTIFICATION,
    PAGE,
    LINK,
    IMAGE
}
