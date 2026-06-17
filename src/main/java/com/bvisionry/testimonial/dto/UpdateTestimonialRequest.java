package com.bvisionry.testimonial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update — only non-null fields are applied. {@code rating} is boxed so
 * "not provided" is distinguishable from a value; when present it must be 1–5.
 */
public record UpdateTestimonialRequest(
        @Size(max = 160, message = "Name must be 160 characters or less")
        String name,

        @Size(max = 200, message = "Title must be 200 characters or less")
        String title,

        String quote,

        Integer year,

        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        Integer rating,

        @Size(max = 512, message = "Photo URL must be 512 characters or less")
        String photoUrl,

        Boolean published,

        Integer displayOrder
) {}
