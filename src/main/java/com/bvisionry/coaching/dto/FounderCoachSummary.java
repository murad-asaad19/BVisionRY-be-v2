package com.bvisionry.coaching.dto;

import java.util.UUID;

/**
 * One coach as their FOUNDER sees them — {@code GET /api/v1/me/coaches}.
 *
 * <p>Name and booking link only. This is the mirror image of
 * {@code CoachFounderSummary}, which withholds the founder's email from the
 * coach; withholding the coach's email from the founder keeps that symmetric,
 * and the booking page IS the contact channel this ticket exists to provide,
 * so an address adds exposure without adding a capability.
 *
 * @param id         the coach's user id — an opaque handle, no founder-facing
 *                   route consumes it today; present so the card has a stable
 *                   React key when a founder has several coaches
 * @param name       the coach's display name
 * @param bookingUrl their Cal.com page, or null when they have not published one
 */
public record FounderCoachSummary(UUID id, String name, String bookingUrl) {
}
