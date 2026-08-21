package com.bvisionry.coaching.dto;

/**
 * The signed-in coach's own profile — {@code GET/PATCH /api/v1/coach/profile}.
 * Never another coach's: the endpoint takes no id.
 *
 * <p><strong>{@code photoUrl} is the RAW stored value here</strong> — a
 * {@code minio://bucket/key} marker or an external URL — deliberately NOT
 * resolved through {@code MediaUrlPort}. This record is the coach's own WRITE
 * surface: the form reads it and PATCHes it back, so a resolved value would
 * round-trip a short-lived presigned URL into the column and expire there. The
 * founder-facing {@link FounderCoachSummary} is the read surface, and that one
 * resolves.
 *
 * <p>{@code photoDisplayUrl} carries the SAME photo already resolved, purely so
 * the coach can see their own thumbnail. It is never sent back: the form edits
 * {@code photoUrl}. Without it a coach who uploads a file sees only the word
 * "uploaded" and can never confirm what their founders will see.
 *
 * @param bookingUrl      the coach's Cal.com booking page, or null when unpublished
 * @param headline        the one-line subtitle under their name, or null
 * @param bio             a serialised tiptap document, or null
 * @param photoUrl        the stored marker/URL for their photo, or null
 * @param photoDisplayUrl the same photo, loadable by a browser, or null
 */
public record CoachProfileResponse(String bookingUrl, String headline, String bio,
        String photoUrl, String photoDisplayUrl) {
}
