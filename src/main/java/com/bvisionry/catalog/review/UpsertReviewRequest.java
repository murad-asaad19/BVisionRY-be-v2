package com.bvisionry.catalog.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating or updating the current learner's review.
 */
public record UpsertReviewRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        String comment) {
}
