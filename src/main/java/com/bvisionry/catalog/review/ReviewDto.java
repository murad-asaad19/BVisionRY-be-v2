package com.bvisionry.catalog.review;

import java.time.OffsetDateTime;

/**
 * A learner's own review of a course.
 *
 * @param id          review UUID
 * @param courseId    UUID of the reviewed course
 * @param rating      1–5
 * @param comment     optional free-text comment
 * @param authorName  display name of the reviewer
 * @param updatedAt   when the review was last saved
 */
public record ReviewDto(
        String id,
        String courseId,
        int rating,
        String comment,
        String authorName,
        OffsetDateTime updatedAt) {
}
