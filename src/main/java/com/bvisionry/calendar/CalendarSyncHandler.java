package com.bvisionry.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.calendar.provider.CalendarProviders;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.config.FrontendUrls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mirrors a scheduled session onto the coach's real calendar (sessions spec v2
 * §7), so the founders get a Google invite with a Meet link and the coach's own
 * free/busy stops offering the slot to anyone else.
 *
 * <h2>Ordering</h2>
 * {@code @Order(10)} against {@code CoachingSessionEmailHandler}'s
 * {@code @Order(20)}: our confirmation email quotes {@code meeting_url}, so the
 * event has to exist before the mail is composed. Both run AFTER_COMMIT on the
 * publishing thread, so "before" is genuinely sequential here.
 *
 * <h2>Reschedule</h2>
 * A move is a PATCH of the existing event, never a delete plus a create: the
 * appointment did not stop existing, and re-creating it would mint a new Meet
 * room and show every guest a cancellation.
 *
 * <h2>Failure</h2>
 * Nothing thrown from here ever reaches the booking. An AFTER_COMMIT listener
 * cannot roll a committed booking back — it can only abort the rest of the
 * fan-out, which would cost the founder their confirmation email as well. So a
 * calendar outage degrades to exactly what a coach with no connected calendar
 * gets: no event, no Meet link, an {@code .ics} in the mail, and the reason
 * recorded in {@code last_error} where {@code /app/team} shows it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalendarSyncHandler {

    private final CalendarProviders providers;
    private final CalendarConnectionService connections;
    private final FrontendUrls frontendUrls;

    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionBooked(CoachingEvents.SessionBooked event) {
        create(event.coachId(), event.sessionId(), spec(event.sessionId(), event.taskName(),
                event.cohortName(), event.taskId(), event.cohortId(), event.startsAt(),
                event.endsAt(), List.of(event.memberEmail())));
    }

    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionScheduled(CoachingEvents.CohortSessionScheduled event) {
        create(event.coachId(), event.sessionId(), spec(event.sessionId(), event.taskName(),
                event.cohortName(), event.taskId(), event.cohortId(), event.startsAt(),
                event.endsAt(), emails(event.attendees())));
    }

    /**
     * A moved 1:1: the SAME appointment at another time, so the event is patched
     * rather than replaced — guests keep the Meet link they already have and see
     * one "updated" notice instead of a cancellation followed by an invitation.
     */
    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionRescheduled(CoachingEvents.SessionRescheduled event) {
        move(event.coachId(), event.sessionId(), spec(event.sessionId(), event.taskName(),
                event.cohortName(), event.taskId(), event.cohortId(), event.startsAt(),
                event.endsAt(), List.of(event.memberEmail())));
    }

    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionRescheduled(CoachingEvents.CohortSessionRescheduled event) {
        move(event.coachId(), event.sessionId(), spec(event.sessionId(), event.taskName(),
                event.cohortName(), event.taskId(), event.cohortId(), event.startsAt(),
                event.endsAt(), emails(event.attendees())));
    }

    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionCancelled(CoachingEvents.SessionCancelled event) {
        cancel(event.coachId(), event.sessionId());
    }

    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionCancelled(CoachingEvents.CohortSessionCancelled event) {
        cancel(event.coachId(), event.sessionId());
    }

    private CalendarProvider.EventSpec spec(UUID sessionId, String taskName, String cohortName,
                                            UUID taskId, UUID cohortId,
                                            java.time.Instant startsAt, java.time.Instant endsAt,
                                            List<String> attendeeEmails) {
        return new CalendarProvider.EventSpec(
                taskName + " — " + cohortName,
                "Your Bvisionry session: " + frontendUrls.path(
                        "/app/program/tasks/" + taskId + "/session?cohort=" + cohortId),
                startsAt, endsAt, attendeeEmails,
                // The session id: a retried create can never mint a second
                // Meet room for the same session.
                sessionId.toString());
    }

    private static List<String> emails(List<CoachingEvents.Attendee> attendees) {
        return attendees.stream().map(CoachingEvents.Attendee::email).toList();
    }

    private void create(UUID coachId, UUID sessionId, CalendarProvider.EventSpec spec) {
        Connected connected = connectionFor(coachId).orElse(null);
        if (connected == null) {
            return;
        }
        try {
            connections.recordEventCreated(sessionId, coachId,
                    connected.provider().createEvent(connected.connection(), spec));
        } catch (RuntimeException e) {
            log.warn("Calendar sync failed for session {} (coach {}): {}",
                    sessionId, coachId, e.getMessage());
            connections.recordError(coachId, e.getMessage());
        }
    }

    /**
     * Patch the event this session already has. With no event id there is
     * nothing to patch — the coach connected their calendar after the booking,
     * or the first sync failed — so the move is the moment to create one; and an
     * event the coach deleted by hand is the same case, one round trip later.
     */
    private void move(UUID coachId, UUID sessionId, CalendarProvider.EventSpec spec) {
        Connected connected = connectionFor(coachId).orElse(null);
        if (connected == null) {
            return;
        }
        String externalId = connections.calendarEventId(sessionId).orElse(null);
        if (externalId == null) {
            create(coachId, sessionId, spec);
            return;
        }
        try {
            connections.recordEventCreated(sessionId, coachId,
                    connected.provider().updateEvent(connected.connection(), externalId, spec));
        } catch (CalendarProvider.EventNotFound e) {
            create(coachId, sessionId, spec);
        } catch (RuntimeException e) {
            log.warn("Calendar event {} for session {} could not be moved: {}",
                    externalId, sessionId, e.getMessage());
            connections.recordError(coachId, e.getMessage());
        }
    }

    private void cancel(UUID coachId, UUID sessionId) {
        Connected connected = connectionFor(coachId).orElse(null);
        if (connected == null) {
            return;
        }
        String externalId = connections.calendarEventId(sessionId).orElse(null);
        if (externalId == null) {
            return;
        }
        String error = null;
        try {
            connected.provider().deleteEvent(connected.connection(), externalId);
        } catch (RuntimeException e) {
            log.warn("Calendar event {} for session {} could not be deleted: {}",
                    externalId, sessionId, e.getMessage());
            error = e.getMessage();
        }
        connections.recordEventCleared(sessionId, coachId, error);
    }

    private Optional<Connected> connectionFor(UUID coachId) {
        if (coachId == null) {
            return Optional.empty();
        }
        return connections.find(coachId)
                .flatMap(connection -> providers.byId(connection.getProvider())
                        .map(provider -> new Connected(connection, provider)));
    }

    private record Connected(CoachCalendarConnection connection, CalendarProvider provider) {}
}
