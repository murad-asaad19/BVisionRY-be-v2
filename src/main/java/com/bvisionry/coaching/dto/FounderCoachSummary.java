package com.bvisionry.coaching.dto;

import java.util.UUID;

/**
 * One coach as their FOUNDER sees them — {@code GET /api/v1/me/coaches}.
 *
 * <p>Who they are and how to book them; still no email. This is the mirror
 * image of {@code CoachFounderSummary}, which withholds the founder's email from
 * the coach; withholding the coach's email from the founder keeps that
 * symmetric, and the booking page IS the contact channel, so an address adds
 * exposure without adding a capability.
 *
 * @param id         the coach's user id — an opaque handle; the card uses it as
 *                   a stable React key and to mark which of these coaches are
 *                   the caller's OWN (the two scopes are intersected by id)
 * @param name       the coach's display name
 * @param headline   V178: the one-line subtitle under the name, or null
 * @param bio        V178: a serialised tiptap document rendered by
 *                   {@code RichTextView}, or null
 * @param photoUrl   V178: their photo ALREADY THROUGH
 *                   {@link com.bvisionry.common.media.MediaUrlPort} — a raw
 *                   {@code minio://} marker is not loadable by a browser, so a
 *                   caller building this record from the stored column without
 *                   resolving it ships a broken image
 * @param bookingUrl their Cal.com page, or null when they have not published one
 */
public record FounderCoachSummary(UUID id, String name, String headline, String bio,
                                  String photoUrl, String bookingUrl) {
}
