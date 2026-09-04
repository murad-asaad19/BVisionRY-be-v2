package com.bvisionry.calendar.provider;

import java.time.Instant;
import java.util.List;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.common.calendar.TimeRange;

/**
 * One calendar back end (sessions spec v2 §7). Google is the only implementation
 * today; a second provider is a new class carrying a new {@link #id()} plus one
 * more value in the {@code coach_calendar_connections.provider} check — nothing
 * in the sync handler, the busy adapter or the controller changes.
 *
 * <p>Implementations may throw freely. Every caller in this slice fails soft and
 * records the message in {@code last_error}: a booking is never lost, and never
 * rolled back, because a calendar was unreachable.
 */
public interface CalendarProvider {

    /** Stable id stored on the connection row, e.g. {@code GOOGLE}. */
    String id();

    /** Where to send the coach's browser to consent. */
    String authorizationUrl(String state, String redirectUri);

    /** Trade the callback's code for a long-lived grant. */
    Grant exchange(String code, String redirectUri);

    CreatedEvent createEvent(CoachCalendarConnection connection, EventSpec spec);

    /**
     * Move an existing event to the spec's times and guest list, keeping the
     * conference room it already has — a rescheduled session is the SAME
     * appointment, so deleting and re-creating it would drop the Meet link every
     * attendee already has and make their calendars show a cancellation.
     *
     * @throws EventNotFound when the provider no longer has that event; the
     *                       caller creates a fresh one instead
     */
    CreatedEvent updateEvent(CoachCalendarConnection connection, String externalId, EventSpec spec);

    /** Deleting an event that is already gone is a success, not an error. */
    void deleteEvent(CoachCalendarConnection connection, String externalId);

    List<TimeRange> busy(CoachCalendarConnection connection, Instant from, Instant to);

    /** Best-effort: hand the grant back to the provider on disconnect. */
    void revoke(CoachCalendarConnection connection);

    /**
     * The event is gone on the provider's side — someone deleted it in their own
     * calendar. Its own type rather than a status code so the sync handler can
     * tell "recreate this" apart from "the provider is having a day", which is
     * the difference between a session that ends up on a calendar and one that
     * does not.
     */
    class EventNotFound extends RuntimeException {
        public EventNotFound(String message) {
            super(message);
        }
    }

    /** What a completed consent yields: the durable grant and whose account it is. */
    record Grant(String refreshToken, String accountEmail) {}

    /** @param meetingUrl the conferencing link, or null when the provider made none */
    record CreatedEvent(String externalId, String meetingUrl) {}

    /**
     * @param requestId idempotency key for conference creation — the session id,
     *                  so a retried create cannot mint a second meeting room
     */
    record EventSpec(String title, String description, Instant startsAt, Instant endsAt,
                     List<String> attendeeEmails, String requestId) {}
}
