package com.bvisionry.notification.push;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the coaching-session events into the emails of the spec's §10 table
 * — for 1:1 bookings, for the cohort-wide sessions a coach or a super admin
 * dates, and for the moves that used to be told as a cancellation followed by
 * a booking (spec v2 §6.1/§6.2/§6.3). AFTER_COMMIT like every handler next to it:
 * a rolled-back booking must not put a session in anyone's calendar, and the
 * cancel case makes it mandatory rather than merely tidy.
 *
 * <h2>Ordering</h2>
 * {@code @Order(20)}, behind {@code CalendarSyncHandler}'s {@code @Order(10)}.
 * That is what lets these mails quote the Meet link: the calendar slice writes
 * {@code sessions.meeting_url} first, and {@link #meetingUrl(UUID)} reads it
 * back here. Without the ordering the link would be present or missing
 * depending on a race, which is worse than never having it.
 *
 * <p>Each listener then hands off to {@code emailExecutor}: a dated cohort
 * session is one SMTP round trip PER FOUNDER, and none of them may be paid for
 * on the request thread that committed the booking. The ordering above survives
 * because the hand-off happens when the listener is INVOKED, after the
 * synchronous calendar handler has already returned.
 *
 * <p><strong>Times are rendered in the COACH'S zone and say so.</strong> The
 * server knows the coach published "Mondays 09:00 Europe/Berlin" and nothing at
 * all about where the founder is, so an unlabelled local time would be a guess
 * presented as a fact. The .ics carries the real instant, which is what a
 * calendar actually needs.
 *
 * <p>Failures are logged, never rethrown: an AFTER_COMMIT listener that throws
 * cannot roll the booking back, it only loses the rest of the fan-out.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoachingSessionEmailHandler {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** The coach's own list of bookings — a COACH-reachable route (/app/team/**). */
    private static final String COACH_SESSIONS_PATH = "/app/team/sessions";

    /**
     * Nobody in the event payload cancelled a super admin's cancellation, so the
     * mail names the role rather than inventing a person: the event carries only
     * "was it the coach", which is the one distinction the recipients need.
     */
    private static final String ADMIN_ACTOR = "an administrator";

    private final EmailService emailService;
    private final FrontendUrls frontendUrls;
    private final NamedParameterJdbcTemplate jdbc;

    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionBooked(CoachingEvents.SessionBooked event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        String date = DATE.format(event.startsAt().atZone(zone));
        String time = timeWithZone(event.startsAt(), zone);
        int minutes = minutesBetween(event.startsAt(), event.endsAt());
        String meetingUrl = meetingUrl(event.sessionId());
        String summary = event.taskName() + " with " + event.coachName();
        String description = event.taskName() + " (" + event.cohortName() + ") — "
                + event.memberName() + " and " + event.coachName();
        // One .ics per recipient: the ATTENDEE line names whoever this copy is for.
        Ics.Person coach = new Ics.Person(event.coachName(), event.coachEmail());
        byte[] memberIcs = Ics.event(event.sessionId(), event.startsAt(), event.endsAt(),
                summary, description, coach,
                new Ics.Person(event.memberName(), event.memberEmail()));
        byte[] coachIcs = Ics.event(event.sessionId(), event.startsAt(), event.endsAt(),
                summary, description, coach, coach);

        send("booked/member", () -> emailService.sendCoachingSessionBookedMember(
                event.memberEmail(), event.memberName(), event.coachName(), date, time, minutes,
                event.taskName(), event.cohortName(), bookingUrl(event.taskId(), event.cohortId()),
                meetingUrl, memberIcs));
        send("booked/coach", () -> emailService.sendCoachingSessionBookedCoach(
                event.coachEmail(), event.coachName(), event.memberName(), date, time, minutes,
                event.taskName(), event.cohortName(), frontendUrls.path(COACH_SESSIONS_PATH),
                meetingUrl, coachIcs));
    }

    /**
     * A group-coaching or workshop session was dated. Every founder on the
     * roster is told the COHORT meets — the GROUP_SESSION_* pair, not the 1:1
     * "your session is booked" copy, which would tell a room of twelve they each
     * have an appointment of their own. The coach gets one mail naming the size
     * of the room rather than {@code n} copies of it.
     */
    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionScheduled(CoachingEvents.CohortSessionScheduled event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        String date = DATE.format(event.startsAt().atZone(zone));
        String time = timeWithZone(event.startsAt(), zone);
        int minutes = minutesBetween(event.startsAt(), event.endsAt());
        String meetingUrl = meetingUrl(event.sessionId());
        String journeyUrl = bookingUrl(event.taskId(), event.cohortId());
        Ics.Person coach = new Ics.Person(event.coachName(), event.coachEmail());

        for (CoachingEvents.Attendee attendee : event.attendees()) {
            byte[] ics = groupIcs(event, coach, new Ics.Person(attendee.name(), attendee.email()));
            send("scheduled/member", () -> emailService.sendGroupSessionScheduledMember(
                    attendee.email(), attendee.name(), event.coachName(), date, time, minutes,
                    event.taskName(), event.cohortName(), journeyUrl, meetingUrl, ics));
        }
        byte[] coachIcs = groupIcs(event, coach, coach);
        send("scheduled/coach", () -> emailService.sendGroupSessionScheduledCoach(
                event.coachEmail(), event.coachName(), event.attendees().size(), date, time,
                minutes, event.taskName(), event.cohortName(),
                frontendUrls.path(COACH_SESSIONS_PATH), meetingUrl, coachIcs));
    }

    /**
     * A founder moved their own 1:1. Only the COACH is told: the founder picked
     * the new slot themselves and is looking at it.
     */
    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionRescheduled(CoachingEvents.SessionRescheduled event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        // The coach is the only recipient here, and also the organizer.
        Ics.Person coach = new Ics.Person(event.coachName(), event.coachEmail());
        byte[] ics = Ics.event(event.sessionId(), event.startsAt(), event.endsAt(),
                event.taskName() + " with " + event.memberName(),
                event.taskName() + " (" + event.cohortName() + ") — "
                        + event.memberName() + " and " + event.coachName(),
                coach, coach);

        send("rescheduled/coach", () -> emailService.sendSessionRescheduled(
                event.coachEmail(), event.coachName(), event.taskName(), event.cohortName(),
                DATE.format(event.previousStartsAt().atZone(zone)),
                timeWithZone(event.previousStartsAt(), zone),
                DATE.format(event.startsAt().atZone(zone)),
                timeWithZone(event.startsAt(), zone),
                minutesBetween(event.startsAt(), event.endsAt()),
                event.memberName(), meetingUrl(event.sessionId()),
                frontendUrls.path(COACH_SESSIONS_PATH), ics));
    }

    /**
     * A cohort-wide session moved. The founders are always told — none of them
     * moved it. The COACH is told only when someone else did, which is exactly
     * {@code byCoach == false}: an administrator re-dated their session.
     */
    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionRescheduled(CoachingEvents.CohortSessionRescheduled event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        String previousDate = DATE.format(event.previousStartsAt().atZone(zone));
        String previousTime = timeWithZone(event.previousStartsAt(), zone);
        String date = DATE.format(event.startsAt().atZone(zone));
        String time = timeWithZone(event.startsAt(), zone);
        int minutes = minutesBetween(event.startsAt(), event.endsAt());
        String movedBy = event.byCoach() ? event.coachName() : ADMIN_ACTOR;
        String meetingUrl = meetingUrl(event.sessionId());
        String journeyUrl = bookingUrl(event.taskId(), event.cohortId());
        Ics.Person coach = new Ics.Person(event.coachName(), event.coachEmail());

        for (CoachingEvents.Attendee attendee : event.attendees()) {
            byte[] ics = groupIcs(event, coach, new Ics.Person(attendee.name(), attendee.email()));
            send("cohort-rescheduled/member", () -> emailService.sendSessionRescheduled(
                    attendee.email(), attendee.name(), event.taskName(), event.cohortName(),
                    previousDate, previousTime, date, time, minutes, movedBy, meetingUrl,
                    journeyUrl, ics));
        }
        if (!event.byCoach()) {
            byte[] coachIcs = groupIcs(event, coach, coach);
            send("cohort-rescheduled/coach", () -> emailService.sendSessionRescheduled(
                    event.coachEmail(), event.coachName(), event.taskName(), event.cohortName(),
                    previousDate, previousTime, date, time, minutes, movedBy, meetingUrl,
                    frontendUrls.path(COACH_SESSIONS_PATH), coachIcs));
        }
    }

    /** Only the OTHER party is told — whoever pressed cancel already knows. */
    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionCancelled(CoachingEvents.SessionCancelled event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        String date = DATE.format(event.startsAt().atZone(zone));
        String time = timeWithZone(event.startsAt(), zone);
        boolean toMember = event.cancelledByCoach();
        send("cancelled", () -> emailService.sendCoachingSessionCancelled(
                toMember ? event.memberEmail() : event.coachEmail(),
                toMember ? event.memberName() : event.coachName(),
                toMember ? event.coachName() : event.memberName(),
                date, time, event.taskName(),
                // The founder rebooks on their own screen; the coach's
                // equivalent is their sessions list, since they never book.
                toMember ? bookingUrl(event.taskId(), event.cohortId())
                        : frontendUrls.path(COACH_SESSIONS_PATH)));
    }

    /**
     * A cohort-wide session went back to UNSCHEDULED. The founders are always
     * told — none of them cancelled it. The COACH is told only when someone else
     * did: {@code cancelledByCoach} is false exactly when a super admin
     * unscheduled the session (a founder cannot cancel a cohort-wide row), and
     * that is the one case where the coach would otherwise turn up.
     */
    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionCancelled(CoachingEvents.CohortSessionCancelled event) {
        ZoneId zone = zoneOf(event.coachTimeZone());
        String date = DATE.format(event.startsAt().atZone(zone));
        String time = timeWithZone(event.startsAt(), zone);
        String cancelledBy = event.cancelledByCoach() ? event.coachName() : ADMIN_ACTOR;
        String journeyUrl = bookingUrl(event.taskId(), event.cohortId());

        for (CoachingEvents.Attendee attendee : event.attendees()) {
            send("cohort-cancelled/member", () -> emailService.sendCoachingSessionCancelled(
                    attendee.email(), attendee.name(), cancelledBy,
                    date, time, event.taskName(), journeyUrl));
        }
        if (!event.cancelledByCoach()) {
            send("cohort-cancelled/coach", () -> emailService.sendCoachingSessionCancelled(
                    event.coachEmail(), event.coachName(), cancelledBy,
                    date, time, event.taskName(), frontendUrls.path(COACH_SESSIONS_PATH)));
        }
    }

    @Async("emailExecutor")
    @Order(20)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionCompleted(CoachingEvents.SessionCompleted event) {
        send("feedback", () -> emailService.sendCoachingSessionFeedback(
                event.memberEmail(), event.memberName(), event.coachName(), event.taskName(),
                frontendUrls.path("/app/program/tasks/" + event.taskId()
                        + "/survey?cohort=" + event.cohortId())));
    }

    /**
     * The Meet link the calendar slice just wrote, or null when the coach has no
     * connected calendar (or the sync failed). Null rather than "" on purpose:
     * the templates gate the Join line on {@code {{#meetingUrl}}}, and an empty
     * string is TRUTHY to Mustache — it would render "Join:" pointing nowhere.
     */
    private String meetingUrl(UUID sessionId) {
        try {
            return jdbc.queryForList("SELECT meeting_url FROM sessions WHERE id = :id",
                            Map.of("id", sessionId), String.class)
                    .stream()
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read the meeting link for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * One .ics for a whole cohort, built per RECIPIENT because the ATTENDEE
     * line names them. Two overloads rather than seven loose strings at the
     * call sites — the two events carry the same fields under the same names.
     */
    private static byte[] groupIcs(CoachingEvents.CohortSessionScheduled event,
                                   Ics.Person organizer, Ics.Person recipient) {
        return groupIcs(event.sessionId(), event.startsAt(), event.endsAt(), event.taskName(),
                event.cohortName(), event.coachName(), organizer, recipient);
    }

    private static byte[] groupIcs(CoachingEvents.CohortSessionRescheduled event,
                                   Ics.Person organizer, Ics.Person recipient) {
        return groupIcs(event.sessionId(), event.startsAt(), event.endsAt(), event.taskName(),
                event.cohortName(), event.coachName(), organizer, recipient);
    }

    private static byte[] groupIcs(UUID sessionId, Instant startsAt, Instant endsAt,
                                   String taskName, String cohortName, String coachName,
                                   Ics.Person organizer, Ics.Person recipient) {
        return Ics.event(sessionId, startsAt, endsAt, taskName + " with " + coachName,
                taskName + " (" + cohortName + ") with " + coachName, organizer, recipient);
    }

    /** Spec §10: the founder's session screen, which doubles as the rebook link. */
    private String bookingUrl(UUID taskId, UUID cohortId) {
        return frontendUrls.path("/app/program/tasks/" + taskId + "/session?cohort=" + cohortId);
    }

    private static int minutesBetween(Instant startsAt, Instant endsAt) {
        return (int) Duration.between(startsAt, endsAt).toMinutes();
    }

    /**
     * "10:00 (Europe/Berlin)". The zone is part of the VALUE, not decoration —
     * without it a founder two zones away reads a time that is simply wrong.
     */
    private static String timeWithZone(Instant at, ZoneId zone) {
        return TIME.format(at.atZone(zone)) + " (" + zone.getId() + ")";
    }

    /** A coach with no saved zone has no availability either, but fail readable, not loud. */
    private static ZoneId zoneOf(String raw) {
        try {
            return raw == null ? ZoneOffset.UTC : ZoneId.of(raw);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private void send(String what, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.warn("Coaching session email ({}) failed: {}", what, e.getMessage());
        }
    }
}
