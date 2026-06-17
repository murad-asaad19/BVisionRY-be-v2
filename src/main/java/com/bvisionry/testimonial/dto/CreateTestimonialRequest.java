package com.bvisionry.testimonial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTestimonialRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must be 160 characters or less")
        String name,

        @Size(max = 200, message = "Title must be 200 characters or less")
        String title,

        @NotBlank(message = "Testimonial is required")
        String quote,

        Integer year,

        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        int rating,

        @Size(max = 512, message = "Photo URL must be 512 characters or less")
        String photoUrl,

        Boolean published,

        Integer displayOrder
) {}
