package com.bvisionry.coaching.dto;

import com.bvisionry.common.validation.CalComBookingUrl;
import com.bvisionry.common.validation.ValidExternalUrl;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/coach/profile}. One field, so PATCH semantics are
 * unambiguous: null or blank CLEARS the link (that is how a coach stops
 * publishing it), any other value replaces it.
 *
 * <p>Both constraints are present on purpose even though {@link CalComBookingUrl}
 * subsumes {@link ValidExternalUrl}: the composition is the documentation, and
 * a future non-Cal.com field on this record inherits the right default.
 */
public record UpdateCoachProfileRequest(
        @Size(max = 512, message = "Booking link must be at most 512 characters")
        @ValidExternalUrl
        @CalComBookingUrl
        String bookingUrl) {
}
