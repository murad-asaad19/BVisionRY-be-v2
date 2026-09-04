package com.bvisionry.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Coaching-booking domain events (spec §7). They live in {@code common} for the
 * same reason as {@link WorkshopEvents}: {@code coaching} publishes and
 * {@code notification} consumes, and the architecture ratchet forbids either
 * importing the other.
 *
 * <p>Every record carries the full mail payload — both parties' name and email,
 * the times, the coach's zone, the task and cohort names. That is deliberate:
 * the handler runs AFTER_COMMIT, and for a CANCELLED session the row it would
 * otherwise re-read no longer holds the details the mail needs — cancelling
 * reverts it to UNSCHEDULED and clears the time it was booked for.
 */
public final class CoachingEvents {

    private CoachingEvents() {}

    /** A member booked a slot. Emails both sides, each with an .ics. */
    public record SessionBooked(UUID sessionId, UUID memberId, UUID coachId,
                                String memberName, String memberEmail,
                                String coachName, String coachEmail, String coachTimeZone,
                                Instant startsAt, Instant endsAt,
                                UUID taskId, String taskName,
                                UUID cohortId, String cohortName) {}

    /**
     * The booking is gone — the row reverts to UNSCHEDULED and loses its time
     * (spec §2.2), which is why the times below travel in the event. Emails THE
     * OTHER PARTY: {@code cancelledByCoach} says which side that is, since
     * whoever pressed the button already knows.
     */
    public record SessionCancelled(UUID sessionId, UUID memberId, UUID coachId,
                                   String memberName, String memberEmail,
                                   String coachName, String coachEmail, String coachTimeZone,
                                   Instant startsAt, Instant endsAt,
                                   UUID taskId, String taskName,
                                   UUID cohortId, String cohortName,
                                   boolean cancelledByCoach) {}

    /**
     * The coach marked the session held WITH the member present AND the task
     * names a survey — i.e. the feedback CTA is now live (spec §1.4).
     *
     * <p>The decision to filter at the PUBLISHER rather than in the handler:
     * "there is nothing to ask about" is a fact about the task, which the
     * writing transaction already has in hand, and a NO_SHOW has no follow-up
     * of any kind. Publishing every completion and letting the handler drop
     * most of them would make the event a lie about what happened next.
     */
    public record SessionCompleted(UUID sessionId, UUID memberId, String memberName,
                                   String memberEmail, String coachName,
                                   UUID taskId, String taskName,
                                   UUID cohortId, UUID feedbackSurveyId) {}

    /** One invited founder of a cohort-wide session (spec v2 §8). */
    public record Attendee(UUID userId, String name, String email) {}

    /**
     * A group-coaching or workshop session was dated by the coach or a super
     * admin (spec v2 §6.1/§6.2). {@code attendees} is the cohort roster at that
     * moment — the calendar event's guest list and the mail fan-out.
     */
    public record CohortSessionScheduled(UUID sessionId, UUID coachId,
                                         String coachName, String coachEmail, String coachTimeZone,
                                         Instant startsAt, Instant endsAt,
                                         UUID taskId, String taskName,
                                         UUID cohortId, String cohortName,
                                         List<Attendee> attendees) {}

    /**
     * A member moved their own 1:1 to a new slot in ONE step (PUT on the
     * booking). One event, one "Rescheduled" mail to the coach, one calendar
     * update — instead of the cancel + book pair the UI used to send.
     */
    public record SessionRescheduled(UUID sessionId, UUID memberId, UUID coachId,
                                     String memberName, String memberEmail,
                                     String coachName, String coachEmail, String coachTimeZone,
                                     Instant previousStartsAt, Instant previousEndsAt,
                                     Instant startsAt, Instant endsAt,
                                     UUID taskId, String taskName,
                                     UUID cohortId, String cohortName) {}

    /**
     * A SCHEDULED cohort-wide session was moved to a new slot by its coach or a
     * super admin. Attendees always hear; the coach too when {@code byCoach} is
     * false (an admin moved their session).
     */
    public record CohortSessionRescheduled(UUID sessionId, UUID coachId,
                                           String coachName, String coachEmail, String coachTimeZone,
                                           Instant previousStartsAt, Instant previousEndsAt,
                                           Instant startsAt, Instant endsAt,
                                           UUID taskId, String taskName,
                                           UUID cohortId, String cohortName,
                                           List<Attendee> attendees,
                                           boolean byCoach) {}

    /** A scheduled cohort-wide session went back to UNSCHEDULED (spec v2 §6.1). */
    public record CohortSessionCancelled(UUID sessionId, UUID coachId,
                                         String coachName, String coachEmail, String coachTimeZone,
                                         Instant startsAt, Instant endsAt,
                                         UUID taskId, String taskName,
                                         UUID cohortId, String cohortName,
                                         List<Attendee> attendees,
                                         boolean cancelledByCoach) {}
}
