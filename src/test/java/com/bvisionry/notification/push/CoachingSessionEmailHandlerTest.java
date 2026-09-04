package com.bvisionry.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;

/**
 * The cohort-wide fan-out and the Meet link, which are the two things §10 adds
 * to the mails: who gets told when a group session is dated or unscheduled, and
 * that the link the calendar slice just wrote is actually quoted.
 */
class CoachingSessionEmailHandlerTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID COHORT = UUID.randomUUID();
    private static final UUID COACH = UUID.randomUUID();
    private static final String MEET = "https://meet.google.com/abc-defg-hij";

    private EmailService emails;
    private NamedParameterJdbcTemplate jdbc;
    private CoachingSessionEmailHandler handler;

    @BeforeEach
    void setUp() {
        emails = mock(EmailService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl("https://app.test");
        handler = new CoachingSessionEmailHandler(emails, new FrontendUrls(properties), jdbc);
    }

    @Test
    @DisplayName("a scheduled cohort session mails every attendee the GROUP copy, and the coach once")
    void aScheduledCohortSessionMailsTheWholeRoom() {
        meetingUrlIs(MEET);

        handler.onCohortSessionScheduled(scheduled(List.of(
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder One", "one@test.invalid"),
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder Two", "two@test.invalid"))));

        verify(emails).sendGroupSessionScheduledMember(eq("one@test.invalid"), eq("Founder One"),
                eq("Sync Coach"), anyString(), anyString(), eq(60), eq("Group coaching"),
                eq("Spring Cohort"), anyString(), eq(MEET), any());
        verify(emails).sendGroupSessionScheduledMember(eq("two@test.invalid"), eq("Founder Two"),
                eq("Sync Coach"), anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), eq(MEET), any());
        // The coach is told the SIZE of the room, not sent one mail per founder.
        verify(emails).sendGroupSessionScheduledCoach(eq("coach@test.invalid"), eq("Sync Coach"),
                eq(2), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), eq(MEET), any());
        // The 1:1 copy ("your session is booked") must never reach a group room.
        verify(emails, never()).sendCoachingSessionBookedMember(anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("no calendar event means no Meet link — null, so the templates omit the Join line")
    void withoutACalendarEventTheMeetLinkIsNull() {
        meetingUrlIs(null);

        handler.onCohortSessionScheduled(scheduled(List.of(
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder One", "one@test.invalid"))));

        verify(emails).sendGroupSessionScheduledMember(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                eq((String) null), any());
    }

    @Test
    @DisplayName("a member's own move tells the COACH only, with both times and who moved it")
    void aMemberMoveTellsTheCoach() {
        meetingUrlIs(MEET);

        handler.onSessionRescheduled(new CoachingEvents.SessionRescheduled(
                SESSION, UUID.randomUUID(), COACH,
                "Alex Founder", "alex@test.invalid",
                "Sync Coach", "coach@test.invalid", "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T10:45:00Z"),
                Instant.parse("2026-09-17T14:00:00Z"), Instant.parse("2026-09-17T14:45:00Z"),
                TASK, "Coaching 1:1", COHORT, "Spring Cohort"));

        verify(emails).sendSessionRescheduled(eq("coach@test.invalid"), eq("Sync Coach"),
                eq("Coaching 1:1"), eq("Spring Cohort"),
                eq("Monday, 14 September 2026"), eq("10:00 (UTC)"),
                eq("Thursday, 17 September 2026"), eq("14:00 (UTC)"),
                eq(45), eq("Alex Founder"), eq(MEET),
                eq("https://app.test/app/team/sessions"), any());
        // The founder chose the new slot; they do not need to be told about it.
        verify(emails, never()).sendSessionRescheduled(eq("alex@test.invalid"), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a coach's cohort move tells the founders; an admin's tells the coach too")
    void aCohortMoveTellsTheRightPeople() {
        meetingUrlIs(MEET);

        handler.onCohortSessionRescheduled(rescheduled(true));
        verify(emails).sendSessionRescheduled(eq("one@test.invalid"), eq("Founder One"),
                anyString(), anyString(), eq("Monday, 14 September 2026"), anyString(),
                eq("Thursday, 17 September 2026"), anyString(), eq(60), eq("Sync Coach"),
                eq(MEET), anyString(), any());
        verify(emails, never()).sendSessionRescheduled(eq("coach@test.invalid"), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(), any());

        handler.onCohortSessionRescheduled(rescheduled(false));
        verify(emails).sendSessionRescheduled(eq("coach@test.invalid"), eq("Sync Coach"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), eq("an administrator"), anyString(),
                eq("https://app.test/app/team/sessions"), any());
    }

    @Test
    @DisplayName("a coach-cancelled cohort session tells the founders only")
    void aCoachCancellationTellsTheFounders() {
        handler.onCohortSessionCancelled(cancelled(true));

        verify(emails).sendCoachingSessionCancelled(eq("one@test.invalid"), eq("Founder One"),
                eq("Sync Coach"), anyString(), anyString(), anyString(), anyString());
        verify(emails, never()).sendCoachingSessionCancelled(eq("coach@test.invalid"), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an admin cancellation tells the founders AND the coach, who would otherwise turn up")
    void anAdminCancellationTellsTheCoachToo() {
        handler.onCohortSessionCancelled(cancelled(false));

        verify(emails).sendCoachingSessionCancelled(eq("one@test.invalid"), eq("Founder One"),
                eq("an administrator"), anyString(), anyString(), anyString(), anyString());
        verify(emails).sendCoachingSessionCancelled(eq("coach@test.invalid"), eq("Sync Coach"),
                eq("an administrator"), anyString(), anyString(), anyString(),
                eq("https://app.test/app/team/sessions"));
    }

    @Test
    @DisplayName("a 1:1 booking carries the Meet link to both sides")
    void aBookingCarriesTheMeetLinkToBothSides() {
        meetingUrlIs(MEET);

        handler.onSessionBooked(new CoachingEvents.SessionBooked(
                SESSION, UUID.randomUUID(), COACH,
                "Alex Founder", "alex@test.invalid",
                "Sync Coach", "coach@test.invalid", "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T10:45:00Z"),
                TASK, "Coaching 1:1", COHORT, "Spring Cohort"));

        verify(emails).sendCoachingSessionBookedMember(eq("alex@test.invalid"), eq("Alex Founder"),
                eq("Sync Coach"), anyString(), anyString(), eq(45), anyString(), anyString(),
                anyString(), eq(MEET), any());
        verify(emails).sendCoachingSessionBookedCoach(eq("coach@test.invalid"), eq("Sync Coach"),
                eq("Alex Founder"), anyString(), anyString(), eq(45), anyString(), anyString(),
                anyString(), eq(MEET), any());
    }

    @Test
    @DisplayName("a failing meeting-url lookup costs the link, never the email")
    void aFailedLookupStillSendsTheMail() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenThrow(new IllegalStateException("database is having a day"));

        handler.onCohortSessionScheduled(scheduled(List.of(
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder One", "one@test.invalid"))));

        verify(emails).sendGroupSessionScheduledMember(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                eq((String) null), any());
    }

    @Test
    @DisplayName("each recipient's .ics names THEM as the attendee, with the coach as organizer")
    void everyRecipientGetsTheirOwnInvite() {
        meetingUrlIs(MEET);

        handler.onCohortSessionScheduled(scheduled(List.of(
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder One", "one@test.invalid"),
                new CoachingEvents.Attendee(UUID.randomUUID(), "Founder Two", "two@test.invalid"))));

        ArgumentCaptor<byte[]> invites = ArgumentCaptor.forClass(byte[].class);
        verify(emails, times(2)).sendGroupSessionScheduledMember(anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), invites.capture());

        assertThat(invites.getAllValues().stream().map(CoachingSessionEmailHandlerTest::text))
                .anyMatch(ics -> ics.contains("ATTENDEE;RSVP=TRUE;CN=\"Founder One\""
                        + ":mailto:one@test.invalid"))
                .anyMatch(ics -> ics.contains("ATTENDEE;RSVP=TRUE;CN=\"Founder Two\""
                        + ":mailto:two@test.invalid"))
                .allMatch(ics -> ics.contains(
                        "ORGANIZER;CN=\"Sync Coach\":mailto:coach@test.invalid"));
    }

    /* --------------------------------------------------------------- helpers */

    private static String text(byte[] ics) {
        return new String(ics, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void meetingUrlIs(String url) {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenReturn(java.util.Collections.singletonList(url));
    }

    private static CoachingEvents.CohortSessionScheduled scheduled(
            List<CoachingEvents.Attendee> attendees) {
        return new CoachingEvents.CohortSessionScheduled(SESSION, COACH,
                "Sync Coach", "coach@test.invalid", "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                TASK, "Group coaching", COHORT, "Spring Cohort", attendees);
    }

    private static CoachingEvents.CohortSessionRescheduled rescheduled(boolean byCoach) {
        return new CoachingEvents.CohortSessionRescheduled(SESSION, COACH,
                "Sync Coach", "coach@test.invalid", "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                Instant.parse("2026-09-17T14:00:00Z"), Instant.parse("2026-09-17T15:00:00Z"),
                TASK, "Group coaching", COHORT, "Spring Cohort",
                List.of(new CoachingEvents.Attendee(
                        UUID.randomUUID(), "Founder One", "one@test.invalid")),
                byCoach);
    }

    private static CoachingEvents.CohortSessionCancelled cancelled(boolean byCoach) {
        return new CoachingEvents.CohortSessionCancelled(SESSION, COACH,
                "Sync Coach", "coach@test.invalid", "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                TASK, "Group coaching", COHORT, "Spring Cohort",
                List.of(new CoachingEvents.Attendee(
                        UUID.randomUUID(), "Founder One", "one@test.invalid")),
                byCoach);
    }
}
