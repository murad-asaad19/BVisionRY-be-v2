package com.bvisionry.testimonial.dto;

import com.bvisionry.testimonial.entity.Testimonial;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing view. {@code photoUrl} is the raw stored value (a {@code minio://}
 * marker or external URL) so it round-trips back into the edit form unchanged,
 * while {@code photoDisplayUrl} is the resolved, browser-loadable URL for preview.
 */
public record TestimonialResponse(
        UUID id,
        String name,
        String title,
        String quote,
        Integer year,
        int rating,
        String photoUrl,
        String photoDisplayUrl,
        boolean published,
        int displayOrder,
        Instant createdAt,
        Instant updatedAt
) {
    public static TestimonialResponse from(Testimonial t, String photoDisplayUrl) {
        return new TestimonialResponse(
                t.getId(), t.getName(), t.getTitle(), t.getQuote(), t.getYear(),
                t.getRating(), t.getPhotoUrl(), photoDisplayUrl, t.isPublished(),
                t.getDisplayOrder(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
