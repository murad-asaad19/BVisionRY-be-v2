package com.bvisionry.coaching.dto;

/**
 * The signed-in coach's own profile — {@code GET/PATCH /api/v1/coach/profile}.
 * Never another coach's: the endpoint takes no id.
 *
 * @param bookingUrl the coach's Cal.com booking page, or null when unpublished
 */
public record CoachProfileResponse(String bookingUrl) {
}
