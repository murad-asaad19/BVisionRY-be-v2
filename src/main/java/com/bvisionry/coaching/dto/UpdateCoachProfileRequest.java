package com.bvisionry.coaching.dto;

import com.bvisionry.common.validation.CalComBookingUrl;
import com.bvisionry.common.validation.ValidExternalUrl;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/coach/profile}.
 *
 * <p><strong>WHOLE-PROFILE replace, not per-field merge.</strong> Every field is
 * applied on every call: null or blank CLEARS it (that is how a coach withdraws
 * a booking link or removes their photo), any other value replaces it. V153
 * could leave that implicit because there was one field; with four, "absent
 * means unchanged" would need a tri-state wrapper per field to tell absent from
 * explicit-null, and one form saves all four together anyway. So the rule is
 * one sentence instead of four wrappers: send the profile you want to end up
 * with.
 *
 * <p>Both URL constraints are present on {@code bookingUrl} on purpose even
 * though {@link CalComBookingUrl} subsumes {@link ValidExternalUrl}: the
 * composition is the documentation.
 *
 * @param bookingUrl their Cal.com page — the ONLY field with a host allowlist,
 *                   because it is the only one a founder's browser navigates to
 * @param headline   one line under the name; capped to the varchar(160) column
 * @param bio        a serialised tiptap document. Uncapped, matching the
 *                   {@code text} column and {@code UpsertExerciseTemplateRequest}'s
 *                   equally-uncapped rich {@code description}
 * @param photoUrl   a {@code minio://} marker from the upload dropzone, or an
 *                   external image URL; capped to the varchar(500) column
 */
public record UpdateCoachProfileRequest(
        @Size(max = 512, message = "Booking link must be at most 512 characters")
        @ValidExternalUrl
        @CalComBookingUrl
        String bookingUrl,

        @Size(max = 160, message = "Headline must be at most 160 characters")
        String headline,

        String bio,

        @Size(max = 500, message = "Photo URL must be at most 500 characters")
        String photoUrl) {
}
