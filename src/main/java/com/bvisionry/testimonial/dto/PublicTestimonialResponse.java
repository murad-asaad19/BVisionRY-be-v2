package com.bvisionry.testimonial.dto;

import com.bvisionry.testimonial.entity.Testimonial;

import java.util.UUID;

/**
 * Public-site view. {@code photoUrl} is the resolved, browser-loadable URL — the
 * internal storage marker is never exposed on the unauthenticated endpoint.
 */
public record PublicTestimonialResponse(
        UUID id,
        String name,
        String title,
        String quote,
        Integer year,
        int rating,
        String photoUrl
) {
    public static PublicTestimonialResponse from(Testimonial t, String resolvedPhotoUrl) {
        return new PublicTestimonialResponse(
                t.getId(), t.getName(), t.getTitle(), t.getQuote(), t.getYear(),
                t.getRating(), resolvedPhotoUrl);
    }
}
