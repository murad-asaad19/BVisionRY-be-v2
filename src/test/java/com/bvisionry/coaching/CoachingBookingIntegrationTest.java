package com.bvisionry.coaching;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.coaching.domain.SlotEngine;
import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CoachAvailabilityResponse;
import com.bvisionry.coaching.dto.CoachSessionsResponse;
import com.bvisionry.coaching.dto.CoachSessionsResponse.CoachSessionDto;
import com.bvisionry.coaching.dto.MemberBookingResponse;
import com.bvisionry.coaching.dto.UpsertAvailabilityRequest;
import com.bvisionry.coaching.dto.UpsertAvailabilityRequest.BlockUpsert;
import com.bvisionry.coaching.dto.UpsertAvailabilityRequest.RuleUpsert;
import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.coaching.repository.SessionMaterializer;
import com.bvisionry.coaching.web.CoachScheduleService;
import com.bvisionry.coaching.web.CohortSessionSchedulingService;
import com.bvisionry.coaching.web.MemberBookingService;
import com.bvisionry.coaching.web.SessionAutoCompleteJob;
import com.bvisionry.common.calendar.CalendarBusyPort;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailTemplateMetadata;
import com.bvisionry.notification.EmailTemplateRenderer;
import com.bvisionry.notification.entity.EmailTemplateKey;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The session loop end to end against the real schema (sessions spec v2 §2–§6,
 * §9): rows are materialised before anyone dates them, booking and scheduling
 * are the same UPDATE, cancelling is its inverse, and the two database guards —
 * one row per (task, member), no overlapping SCHEDULED session for a coach —
 * are the ones actually holding the line.
 *
 * <p>{@link CalendarBusyPort} is mocked rather than left to the calendar slice's
 * adapter: the coach's REAL calendar is the one input this slice cannot create
 * from its own tables, and "that time is busy on Google" has no other way to be
 * exercised. Mockito answers an empty list by default, which is exactly what an
 * unconnected coach looks like.
 *
 * <p>Each 409 that comes from a CONSTRAINT lives at the end of its own test
 * method on purpose: Postgres aborts the transaction on a failed statement, so
 * anything asserted after it in the same test would fail for the wrong reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@EnabledIfDockerAvailable
class CoachingBookingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemberBookingService memberBookings;
    @Autowired private CoachScheduleService coachSchedule;
    @Autowired private CohortSessionSchedulingService cohortScheduling;
    @Autowired private SessionMaterializer materializer;
    @Autowired private CoachingBookingRepository bookings;
    @Autowired private SessionAutoCompleteJob autoComplete;
    @Autowired private EmailTemplateRenderer emailTemplates;
    @Autowired private FrontendUrls frontendUrls;
    @Autowired private ApplicationEvents publishedEvents;

    @MockitoBean private CalendarBusyPort calendarBusy;

    private Organization org;
    private User coach;
    private User member;
    private User otherMember;
    private UUID cohortId;
    private UUID moduleId;
    private UUID taskId;
    private UUID groupTaskId;
    private UUID surveyId;

    @BeforeEach
    void seed() {
        org = saveOrg("Coaching Booking Org");
        coach = saveUser("booking.coach@test.invalid", UserRole.COACH);
        member = saveUser("booking.member@test.invalid", UserRole.MEMBER);
        otherMember = saveUser("booking.other@test.invalid", UserRole.MEMBER);

        // Org-wide grant (V176): both founders are this coach's, and the grant
        // reaches the cohort through cohort_orgs (spec v2 §5).
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, NULL, NULL)
                """, org.getId(), coach.getId());

        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Booking Cohort', 'LAUNCHED')",
                cohortId);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohortId, org.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohortId, member.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohortId, otherMember.getId());

        moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Coaching Module', 'ALL', 'UNLOCKED')
                """, moduleId, cohortId);

        surveyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO surveys (id, name, status, created_by)
                VALUES (?, 'Post-session feedback', 'PUBLISHED', ?)
                """, surveyId, coach.getId());

        // A SESSION task owns its config on the row: no ref_id (spec §3.1).
        taskId = saveSessionTask("Coaching 1:1", "COACHING_1ON1", 0, surveyId);
        groupTaskId = saveSessionTask("Group coaching", "COACHING_GROUP", 1, surveyId);

        jdbc.update("INSERT INTO coach_profiles (coach_id, time_zone) VALUES (?, 'UTC')",
                coach.getId());
        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.update("""
                    INSERT INTO coach_availability_rules (coach_id, weekday, start_time, end_time)
                    VALUES (?, ?, TIME '09:00', TIME '17:00')
                    """, coach.getId(), weekday);
        }
        materializer.sync(cohortId);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------------- materialisation */

    @Test
    @DisplayName("sync writes one UNSCHEDULED row per member for a 1:1 task and exactly one for a group task")
    void syncMaterialisesBothShapes() {
        assertThat(unscheduledMemberIds(taskId))
                .containsExactlyInAnyOrder(member.getId(), otherMember.getId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE program_task_id = ? AND member_id IS NULL
                """, Integer.class, groupTaskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT type || '/' || booking_status FROM sessions WHERE program_task_id = ?
                """, String.class, groupTaskId)).isEqualTo("COACHING_GROUP/UNSCHEDULED");

        // The 1:1 row's single expected attendee is its member; the cohort-wide
        // row has none (V163: empty means the whole cohort).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM session_expected_attendees sea
                JOIN sessions s ON s.id = sea.session_id
                WHERE s.program_task_id = ? AND sea.member_id = s.member_id
                """, Integer.class, taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM session_expected_attendees sea
                JOIN sessions s ON s.id = sea.session_id WHERE s.program_task_id = ?
                """, Integer.class, groupTaskId)).isZero();
    }

    @Test
    @DisplayName("sync is idempotent: running it again writes nothing new")
    void syncIsIdempotent() {
        int before = jdbc.queryForObject("SELECT count(*) FROM sessions WHERE cohort_id = ?",
                Integer.class, cohortId);
        materializer.sync(cohortId);
        materializer.sync(cohortId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE cohort_id = ?",
                Integer.class, cohortId)).isEqualTo(before);
    }

    @Test
    @DisplayName("cleanup drops the UNSCHEDULED row of a member who left, and of a task that is no longer live")
    void syncCleansUpOnlyUnscheduledRows() {
        jdbc.update("DELETE FROM cohort_members WHERE cohort_id = ? AND user_id = ?",
                cohortId, otherMember.getId());
        jdbc.update("UPDATE program_tasks SET status = 'DRAFT' WHERE id = ?", groupTaskId);

        materializer.sync(cohortId);

        assertThat(unscheduledMemberIds(taskId)).containsExactly(member.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE program_task_id = ?",
                Integer.class, groupTaskId)).isZero();
    }

    @Test
    @DisplayName("cleanup never touches a SCHEDULED row, even when the member has left")
    void syncLeavesScheduledRowsAlone() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();

        jdbc.update("DELETE FROM cohort_members WHERE cohort_id = ? AND user_id = ?",
                cohortId, member.getId());
        materializer.sync(cohortId);

        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("SCHEDULED");
    }

    /* ------------------------------------------------------------- the member */

    @Test
    @DisplayName("before booking: the task, one bookable coach, an UNSCHEDULED row")
    void theTaskShowsOneCoachAndAnUnscheduledRow() {
        TestAuthentication.authenticate(member);
        MemberBookingResponse response = memberBookings.booking(taskId);

        assertThat(response.taskId()).isEqualTo(taskId);
        assertThat(response.taskName()).isEqualTo("Coaching 1:1");
        assertThat(response.sessionType()).isEqualTo("COACHING_1ON1");
        assertThat(response.durationMinutes()).isEqualTo(45);
        assertThat(response.booking()).isNotNull();
        assertThat(response.booking().bookingStatus()).isEqualTo("UNSCHEDULED");
        assertThat(response.booking().coachId()).isNull();
        assertThat(response.booking().startsAt()).isNull();
        assertThat(response.booking().attended()).isNull();
        assertThat(response.feedbackSurveyId()).isEqualTo(surveyId);
        assertThat(response.feedbackPending()).isFalse();
        assertThat(response.coaches()).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo(coach.getId()));
    }

    @Test
    @DisplayName("a cohort-wide task offers no coaches and refuses every member write")
    void aCohortWideTaskIsReadOnlyForTheMember() {
        TestAuthentication.authenticate(member);
        MemberBookingResponse response = memberBookings.booking(groupTaskId);
        assertThat(response.sessionType()).isEqualTo("COACHING_GROUP");
        assertThat(response.coaches()).isEmpty();
        assertThat(response.booking().bookingStatus()).isEqualTo("UNSCHEDULED");

        Instant slot = firstSlot();
        assertThatThrownBy(() -> memberBookings.book(groupTaskId, coach.getId(), slot))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scheduled by your coach");
        assertThatThrownBy(() -> memberBookings.cancel(groupTaskId))
                .isInstanceOf(BadRequestException.class);
        Instant now = Instant.now();
        assertThatThrownBy(() -> memberBookings.slots(groupTaskId, coach.getId(), now,
                now.plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a coach with no published availability is not offered")
    void aCoachWithoutAvailabilityIsNotBookable() {
        jdbc.update("DELETE FROM coach_availability_rules WHERE coach_id = ?", coach.getId());
        TestAuthentication.authenticate(member);
        assertThat(memberBookings.booking(taskId).coaches()).isEmpty();
    }

    @Test
    @DisplayName("slots are offered, none of them inside the 12-hour minimum notice")
    void slotsRespectTheMinimumNotice() {
        TestAuthentication.authenticate(member);
        Instant now = Instant.now();
        BookingSlotsResponse slots = memberBookings.slots(taskId, coach.getId(), now, now.plus(7, ChronoUnit.DAYS));

        assertThat(slots.timeZone()).isEqualTo("UTC");
        assertThat(slots.slots()).isNotEmpty();
        assertThat(slots.slots()).allSatisfy(s ->
                assertThat(s).isAfterOrEqualTo(now.plus(SlotEngine.MIN_NOTICE)));
    }

    @Test
    @DisplayName("a range wider than the 8-week horizon is refused")
    void tooWideARangeIsRefused() {
        TestAuthentication.authenticate(member);
        Instant now = Instant.now();
        assertThatThrownBy(() -> memberBookings.slots(taskId, coach.getId(), now, now.plus(60, ChronoUnit.DAYS)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("booking UPDATES the materialised row rather than inserting a second one")
    void bookingUpdatesTheUnscheduledRow() {
        TestAuthentication.authenticate(member);
        UUID before = jdbc.queryForObject("""
                SELECT id FROM sessions WHERE program_task_id = ? AND member_id = ?
                """, UUID.class, taskId, member.getId());
        Instant slot = firstSlot();

        MemberBookingResponse after = memberBookings.book(taskId, coach.getId(), slot);

        assertThat(after.booking().id()).isEqualTo(before);
        assertThat(after.booking().startsAt()).isEqualTo(slot);
        assertThat(after.booking().bookingStatus()).isEqualTo("SCHEDULED");
        assertThat(after.booking().coachId()).isEqualTo(coach.getId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE program_task_id = ? AND member_id = ?
                """, Integer.class, taskId, member.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT type || '/' || booking_status FROM sessions WHERE id = ?
                """, String.class, before)).isEqualTo("COACHING_1ON1/SCHEDULED");

        // The engine must not go on offering an instant the coach now holds — to
        // ANYONE ELSE. The booker's own picker keeps showing it, because a row
        // cannot make itself unbookable: that is what lets them move off it.
        TestAuthentication.authenticate(otherMember);
        assertThat(slotsForTheNextWeek()).doesNotContain(slot);
    }

    @Test
    @DisplayName("booking still works when materialisation has not run for this member")
    void bookingMaterialisesItsOwnRowWhenSyncHasNotRun() {
        jdbc.update("DELETE FROM sessions WHERE program_task_id = ? AND member_id = ?",
                taskId, member.getId());
        TestAuthentication.authenticate(member);

        MemberBookingResponse after = memberBookings.book(taskId, coach.getId(), firstSlot());

        assertThat(after.booking().bookingStatus()).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForList("""
                SELECT member_id FROM session_expected_attendees WHERE session_id = ?
                """, UUID.class, after.booking().id())).containsExactly(member.getId());
    }

    @Test
    @DisplayName("an instant the coach never published is refused")
    void anUnofferedInstantIsRefused() {
        TestAuthentication.authenticate(member);
        // 03:00 UTC — nowhere near the 09:00-17:00 windows.
        Instant outside = Instant.now().plus(3, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS);
        assertThatThrownBy(() -> memberBookings.book(taskId, coach.getId(), outside))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("an instant the coach's real calendar already holds is a 409")
    void anExternallyBusyInstantIsRefused() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        when(calendarBusy.busy(eq(coach.getId()), any(), any()))
                .thenReturn(List.of(new TimeRange(slot.minusSeconds(600), slot.plusSeconds(600))));

        assertThatThrownBy(() -> memberBookings.book(taskId, coach.getId(), slot))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("busy on the coach's calendar");
        // …and the picker stops offering it, so the 409 is a race guard, not the UI.
        assertThat(slotsForTheNextWeek()).doesNotContain(slot);
    }

    @Test
    @DisplayName("a second booking for the same task is a 409")
    void bookingTwiceIsRefused() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        memberBookings.book(taskId, coach.getId(), slot);

        assertThatThrownBy(() -> memberBookings.book(taskId, coach.getId(), slot))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already have a session");
    }

    @Test
    @DisplayName("the database refuses a second member the same coach slot")
    void theExclusionConstraintStopsADoubleBooking() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        memberBookings.book(taskId, coach.getId(), slot);

        // LAST assertion in this method: the constraint aborts the transaction.
        TestAuthentication.authenticate(otherMember);
        assertThatThrownBy(() -> memberBookings.book(taskId, coach.getId(), slot))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("just taken");
    }

    @Test
    @DisplayName("the member cancels: the row REVERTS to UNSCHEDULED and can be rebooked")
    void cancelRevertsTheRowAndAllowsRebooking() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        UUID sessionId = memberBookings.book(taskId, coach.getId(), slot).booking().id();

        memberBookings.cancel(taskId);

        assertThat(jdbc.queryForList("""
                SELECT booking_status || '/' || coalesce(coach_id::text, 'null')
                       || '/' || coalesce(session_date::text, 'null')
                FROM sessions WHERE id = ?
                """, String.class, sessionId)).containsExactly("UNSCHEDULED/null/null");
        assertThat(memberBookings.booking(taskId).booking().bookingStatus()).isEqualTo("UNSCHEDULED");

        // Rebook: the slot is free again and it is the SAME row.
        assertThat(memberBookings.book(taskId, coach.getId(), slot).booking().id())
                .isEqualTo(sessionId);
    }

    @Test
    @DisplayName("the member MOVES their booking in one step: same row, one event, no cancellation")
    void rescheduleMovesTheRowInPlace() {
        TestAuthentication.authenticate(member);
        List<Instant> offered = slotsForTheNextWeek();
        Instant first = offered.getFirst();
        UUID sessionId = memberBookings.book(taskId, coach.getId(), first).booking().id();

        // The picker still offers the slot this session itself holds \u2014 otherwise
        // the reschedule dialog would hide the row's own time and everything
        // overlapping it.
        assertThat(slotsForTheNextWeek()).contains(first);
        Instant moved = slotsForTheNextWeek().stream().filter(s -> !s.equals(first))
                .findFirst().orElseThrow();

        MemberBookingResponse after = memberBookings.reschedule(taskId, coach.getId(), moved);

        assertThat(after.booking().id()).isEqualTo(sessionId);
        assertThat(after.booking().startsAt()).isEqualTo(moved);
        assertThat(after.booking().bookingStatus()).isEqualTo("SCHEDULED");
        assertThat(publishedEvents.stream(CoachingEvents.SessionRescheduled.class)).singleElement()
                .satisfies(e -> {
                    assertThat(e.previousStartsAt()).isEqualTo(first);
                    assertThat(e.startsAt()).isEqualTo(moved);
                });
        // The pair this replaces would have emitted these; one move is one event.
        assertThat(publishedEvents.stream(CoachingEvents.SessionCancelled.class)).isEmpty();
        assertThat(publishedEvents.stream(CoachingEvents.SessionBooked.class)).hasSize(1);
    }

    @Test
    @DisplayName("moving to the slot the session already holds changes nothing and tells nobody")
    void reschedulingToTheSameSlotIsANoOp() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        memberBookings.book(taskId, coach.getId(), slot);

        memberBookings.reschedule(taskId, coach.getId(), slot);

        assertThat(publishedEvents.stream(CoachingEvents.SessionRescheduled.class)).isEmpty();
    }

    @Test
    @DisplayName("only a SCHEDULED, not-yet-started 1:1 of the caller's can be moved")
    void whatCannotBeMoved() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();
        UUID coachId = coach.getId();

        // Nothing booked yet.
        assertThatThrownBy(() -> memberBookings.reschedule(taskId, coachId, slot))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("can no longer be moved");
        // A cohort-wide task answers exactly what the POST answers.
        assertThatThrownBy(() -> memberBookings.reschedule(groupTaskId, coachId, slot))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scheduled by your coach");

        UUID sessionId = memberBookings.book(taskId, coachId, slot).booking().id();
        moveIntoThePast(sessionId);
        assertThatThrownBy(() -> memberBookings.reschedule(taskId, coachId, slot))
                .isInstanceOf(IllegalOperationException.class);
    }

    @Test
    @DisplayName("a cohort-wide task reports how many learners are expected; a 1:1 reports null")
    void attendeeCountIsCohortWideOnly() {
        TestAuthentication.authenticate(member);

        assertThat(memberBookings.booking(taskId).attendeeCount()).isNull();
        assertThat(memberBookings.booking(groupTaskId).attendeeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a started session can no longer be cancelled by the member")
    void aStartedSessionCannotBeCancelled() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();
        moveIntoThePast(sessionId);

        assertThatThrownBy(() -> memberBookings.cancel(taskId))
                .isInstanceOf(IllegalOperationException.class);
    }

    /* -------------------------------------------------------------- the coach */

    @Test
    @DisplayName("the coach's console splits unscheduled, upcoming and past")
    void theCoachConsoleSplitsTheThreeBuckets() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();

        TestAuthentication.authenticate(coach);
        CoachSessionsResponse listed = coachSchedule.sessions();

        assertThat(listed.past()).isEmpty();
        assertThat(listed.upcoming()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(sessionId);
            assertThat(s.sessionType()).isEqualTo("COACHING_1ON1");
            assertThat(s.memberId()).isEqualTo(member.getId());
            assertThat(s.taskId()).isEqualTo(taskId);
            assertThat(s.cohortName()).isEqualTo("Booking Cohort");
            assertThat(s.bookingStatus()).isEqualTo("SCHEDULED");
            assertThat(s.canSchedule()).isFalse();
            assertThat(s.attendees()).singleElement()
                    .satisfies(a -> assertThat(a.present()).isFalse());
        });
        // otherMember's untouched 1:1 row plus the group row the coach may date,
        // ordered by task name and, within a task, cohort-wide row first
        // (spec \u00a76.1) \u2014 a to-do list reads by WHAT it is, not by when.
        assertThat(listed.unscheduled()).hasSize(2);
        assertThat(listed.unscheduled()).extracting(CoachSessionDto::taskName,
                        CoachSessionDto::memberName)
                .containsExactly(tuple("Coaching 1:1", otherMember.getName()),
                        tuple("Group coaching", null));
        assertThat(listed.unscheduled()).filteredOn(s -> s.taskId().equals(groupTaskId))
                .singleElement().satisfies(s -> {
                    assertThat(s.canSchedule()).isTrue();
                    assertThat(s.memberId()).isNull();
                    // A cohort-wide row's roll call is the CURRENT roster.
                    assertThat(s.attendees()).hasSize(2);
                });
    }

    @Test
    @DisplayName("the coach marks the roll call, corrects it, and a missed 1:1 is reopened")
    void theCoachCompletesCorrectsAndReopens() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();

        TestAuthentication.authenticate(coach);
        // A session that has not started yet cannot be marked.
        assertThatThrownBy(() -> coachSchedule.complete(sessionId, List.of(member.getId())))
                .isInstanceOf(BadRequestException.class);

        moveIntoThePast(sessionId);
        coachSchedule.complete(sessionId, List.of(member.getId()));

        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("""
                SELECT marked_by FROM session_attendance WHERE session_id = ? AND member_id = ?
                """, UUID.class, sessionId, member.getId())).containsExactly(coach.getId());

        TestAuthentication.authenticate(member);
        MemberBookingResponse done = memberBookings.booking(taskId);
        assertThat(done.booking().bookingStatus()).isEqualTo("COMPLETED");
        assertThat(done.booking().attended()).isTrue();
        assertThat(done.feedbackPending()).isTrue();

        // The coach corrects the mark: held, but they were not there.
        TestAuthentication.authenticate(coach);
        coachSchedule.setAttendance(sessionId, member.getId(), false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_attendance WHERE session_id = ?",
                Integer.class, sessionId)).isZero();
        TestAuthentication.authenticate(member);
        assertThat(memberBookings.booking(taskId).booking().attended()).isFalse();
        assertThat(memberBookings.booking(taskId).feedbackPending()).isFalse();

        // A missed 1:1 goes back to UNSCHEDULED so they can rebook.
        TestAuthentication.authenticate(coach);
        coachSchedule.reopen(sessionId);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("UNSCHEDULED");
        // The row lost its dates, so the Google event has to go with them —
        // same event the calendar handler acts on for a cancellation.
        assertThat(publishedEvents.stream(CoachingEvents.SessionCancelled.class)).singleElement()
                .satisfies(e -> {
                    assertThat(e.sessionId()).isEqualTo(sessionId);
                    assertThat(e.cancelledByCoach()).isTrue();
                });

        // A reopened row has no coach any more, so it is not even the caller's
        // to mark: a 404, the same answer as a stranger's session id.
        assertThatThrownBy(() -> coachSchedule.complete(sessionId, List.of(member.getId())))
                .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("completing with nobody present writes no attendance row")
    void anEmptyRollCallLeavesNoAttendance() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();
        moveIntoThePast(sessionId);

        TestAuthentication.authenticate(coach);
        coachSchedule.complete(sessionId, List.of());

        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_attendance WHERE session_id = ?",
                Integer.class, sessionId)).isZero();

        TestAuthentication.authenticate(member);
        assertThat(memberBookings.booking(taskId).feedbackPending()).isFalse();
    }

    @Test
    @DisplayName("someone who is not expected at the session cannot be marked present")
    void anUnexpectedMemberIsRefused() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();
        moveIntoThePast(sessionId);

        TestAuthentication.authenticate(coach);
        assertThatThrownBy(() -> coachSchedule.complete(sessionId,
                List.of(member.getId(), otherMember.getId())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("the coach cancels a booked 1:1 and it reverts to UNSCHEDULED")
    void theCoachCancelsA1on1() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();

        TestAuthentication.authenticate(coach);
        coachSchedule.cancel(sessionId);

        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("UNSCHEDULED");
    }

    @Test
    @DisplayName("another coach's session is a 404, not a 403")
    void aForeignSessionIsInvisibleToACoach() {
        TestAuthentication.authenticate(member);
        UUID sessionId = memberBookings.book(taskId, coach.getId(), firstSlot()).booking().id();

        User otherCoach = saveUser("booking.coach2@test.invalid", UserRole.COACH);
        TestAuthentication.authenticate(otherCoach);
        assertThat(coachSchedule.sessions().upcoming()).isEmpty();
        assertThatThrownBy(() -> coachSchedule.cancel(sessionId))
                .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
    }

    /* --------------------------------------------- cohort-wide scheduling (§6.1/§6.2) */

    @Test
    @DisplayName("the cohort's coach dates the group session, reschedules it, then cancels it")
    void theCoachSchedulesRescheduesAndCancelsTheGroupSession() {
        UUID sessionId = groupSessionId();
        TestAuthentication.authenticate(coach);
        List<Instant> offered = groupSlots(sessionId);
        assertThat(offered).hasSizeGreaterThan(1);

        cohortScheduling.schedule(sessionId, null, offered.getFirst());
        assertThat(jdbc.queryForObject("""
                SELECT booking_status || '/' || coach_id FROM sessions WHERE id = ?
                """, String.class, sessionId)).isEqualTo("SCHEDULED/" + coach.getId());

        // Reschedule: the same coach moves a session that has not started. ONE
        // event \u2014 the cancel + schedule pair this replaces told a whole cohort
        // their session was off and then that it was on again.
        cohortScheduling.schedule(sessionId, null, offered.get(1));
        assertThat(jdbc.queryForObject("SELECT session_date FROM sessions WHERE id = ?",
                java.sql.Timestamp.class, sessionId).toInstant()).isEqualTo(offered.get(1));
        assertThat(publishedEvents.stream(CoachingEvents.CohortSessionRescheduled.class))
                .singleElement().satisfies(e -> {
                    assertThat(e.previousStartsAt()).isEqualTo(offered.getFirst());
                    assertThat(e.startsAt()).isEqualTo(offered.get(1));
                    assertThat(e.byCoach()).isTrue();
                });
        assertThat(publishedEvents.stream(CoachingEvents.CohortSessionScheduled.class)).hasSize(1);
        assertThat(publishedEvents.stream(CoachingEvents.CohortSessionCancelled.class)).isEmpty();

        cohortScheduling.cancel(sessionId, true);
        assertThat(jdbc.queryForObject("""
                SELECT booking_status || '/' || coalesce(coach_id::text, 'null')
                FROM sessions WHERE id = ?
                """, String.class, sessionId)).isEqualTo("UNSCHEDULED/null");
    }

    @Test
    @DisplayName("a coach with no grant on the cohort gets a 403")
    void aStrangerCannotScheduleTheGroupSession() {
        UUID sessionId = groupSessionId();
        User stranger = saveUser("booking.stranger@test.invalid", UserRole.COACH);
        jdbc.update("INSERT INTO coach_profiles (coach_id, time_zone) VALUES (?, 'UTC')",
                stranger.getId());
        jdbc.update("""
                INSERT INTO coach_availability_rules (coach_id, weekday, start_time, end_time)
                VALUES (?, 1, TIME '09:00', TIME '17:00')
                """, stranger.getId());

        TestAuthentication.authenticate(stranger);
        Instant slot = Instant.now().plus(2, ChronoUnit.DAYS);
        assertThatThrownBy(() -> cohortScheduling.schedule(sessionId, null, slot))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(coachSchedule.sessions().unscheduled()).isEmpty();
    }

    @Test
    @DisplayName("a super admin picks a cohort coach and schedules on their calendar")
    void aSuperAdminSchedulesForACoach() {
        UUID sessionId = groupSessionId();
        User admin = saveUser("booking.admin@test.invalid", UserRole.SUPER_ADMIN);
        TestAuthentication.authenticate(admin);

        assertThat(cohortScheduling.scheduling(sessionId).sessionType()).isEqualTo("COACHING_GROUP");
        assertThat(cohortScheduling.scheduling(sessionId).coaches()).singleElement()
                .satisfies(c -> {
                    assertThat(c.id()).isEqualTo(coach.getId());
                    assertThat(c.timeZone()).isEqualTo("UTC");
                });

        Instant now = Instant.now();
        Instant slot = cohortScheduling.slots(sessionId, coach.getId(), now,
                now.plus(7, ChronoUnit.DAYS)).slots().getFirst();
        cohortScheduling.schedule(sessionId, coach.getId(), slot);

        assertThat(jdbc.queryForObject("""
                SELECT booking_status || '/' || coach_id FROM sessions WHERE id = ?
                """, String.class, sessionId)).isEqualTo("SCHEDULED/" + coach.getId());
    }

    @Test
    @DisplayName("a 1:1 row cannot be scheduled through the cohort-wide route")
    void aOneToOneRowIsRefusedByTheSchedulingService() {
        UUID sessionId = jdbc.queryForObject("""
                SELECT id FROM sessions WHERE program_task_id = ? AND member_id = ?
                """, UUID.class, taskId, member.getId());
        TestAuthentication.authenticate(coach);
        Instant slot = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> cohortScheduling.schedule(sessionId, null, slot))
                .isInstanceOf(BadRequestException.class);
    }

    /* --------------------------------------------------- auto-completion (§9) */

    @Test
    @DisplayName("the job marks an ended session held with every expected member present")
    void theJobCompletesEndedSessions() {
        UUID sessionId = groupSessionId();
        TestAuthentication.authenticate(coach);
        cohortScheduling.schedule(sessionId, null, groupSlots(sessionId).getFirst());
        moveIntoThePast(sessionId);

        assertThat(autoComplete.complete(sessionId)).isTrue();

        assertThat(jdbc.queryForObject("SELECT booking_status FROM sessions WHERE id = ?",
                String.class, sessionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("""
                SELECT member_id FROM session_attendance WHERE session_id = ? AND marked_by IS NULL
                """, UUID.class, sessionId))
                .containsExactlyInAnyOrder(member.getId(), otherMember.getId());

        // Idempotent: the row is no longer SCHEDULED, so a second pass is a no-op.
        assertThat(autoComplete.complete(sessionId)).isFalse();

        TestAuthentication.authenticate(member);
        assertThat(memberBookings.booking(groupTaskId).feedbackPending()).isTrue();
    }

    /* -------------------------------------------------------- availability (§6.1) */

    @Test
    @DisplayName("PUT availability is a whole-calendar replace, and the times come back as HH:mm")
    void availabilityIsReplacedWholesale() {
        TestAuthentication.authenticate(coach);
        Instant offFrom = Instant.parse("2026-12-24T00:00:00Z");
        Instant offTo = Instant.parse("2026-12-27T00:00:00Z");

        CoachAvailabilityResponse saved = coachSchedule.replaceAvailability(
                new UpsertAvailabilityRequest("Europe/Berlin",
                        List.of(new RuleUpsert(2, "10:00", "12:00"),
                                new RuleUpsert(2, "13:00", "17:30")),
                        List.of(new BlockUpsert(offFrom, offTo, "Winter break"))));

        assertThat(saved.timeZone()).isEqualTo("Europe/Berlin");
        // The seven seeded 09:00-17:00 windows are GONE - replace, not merge.
        assertThat(saved.rules()).hasSize(2);
        assertThat(saved.rules().getFirst().weekday()).isEqualTo(2);
        assertThat(saved.rules().getFirst().startTime()).isEqualTo("10:00");
        assertThat(saved.rules().getLast().endTime()).isEqualTo("17:30");
        assertThat(saved.blocks()).singleElement().satisfies(b -> {
            assertThat(b.startsAt()).isEqualTo(offFrom);
            assertThat(b.reason()).isEqualTo("Winter break");
        });
        assertThat(coachSchedule.availability().rules()).hasSize(2);
    }

    @Test
    @DisplayName("an unresolvable zone, an inverted window and two overlapping windows are all 400s")
    void availabilityValidationRefusesTheThreeRelationalErrors() {
        TestAuthentication.authenticate(coach);
        assertThatThrownBy(() -> coachSchedule.replaceAvailability(new UpsertAvailabilityRequest(
                "Mars/Olympus", List.of(), List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> coachSchedule.replaceAvailability(new UpsertAvailabilityRequest(
                "UTC", List.of(new RuleUpsert(3, "17:00", "09:00")), List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> coachSchedule.replaceAvailability(new UpsertAvailabilityRequest(
                "UTC", List.of(new RuleUpsert(3, "09:00", "12:00"),
                        new RuleUpsert(3, "11:00", "14:00")), List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> coachSchedule.replaceAvailability(new UpsertAvailabilityRequest(
                "UTC", List.of(new RuleUpsert(3, "9am", "5pm")), List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a blackout removes the slots it covers")
    void aBlackoutRemovesItsSlots() {
        TestAuthentication.authenticate(member);
        Instant slot = firstSlot();

        TestAuthentication.authenticate(coach);
        coachSchedule.replaceAvailability(new UpsertAvailabilityRequest("UTC",
                List.of(new RuleUpsert(1, "09:00", "17:00"), new RuleUpsert(2, "09:00", "17:00"),
                        new RuleUpsert(3, "09:00", "17:00"), new RuleUpsert(4, "09:00", "17:00"),
                        new RuleUpsert(5, "09:00", "17:00"), new RuleUpsert(6, "09:00", "17:00"),
                        new RuleUpsert(7, "09:00", "17:00")),
                List.of(new BlockUpsert(slot.minusSeconds(1800), slot.plusSeconds(1800), "Busy"))));

        TestAuthentication.authenticate(member);
        assertThat(slotsForTheNextWeek()).doesNotContain(slot);
    }

    /* ------------------------------------------------------------- emails (§10) */

    @Test
    @DisplayName("every coaching template renders with the variables the handler supplies")
    void theCoachingEmailTemplatesRender() {
        for (EmailTemplateKey key : List.of(EmailTemplateKey.COACHING_SESSION_BOOKED_MEMBER,
                EmailTemplateKey.COACHING_SESSION_BOOKED_COACH,
                EmailTemplateKey.COACHING_SESSION_CANCELLED,
                EmailTemplateKey.COACHING_SESSION_FEEDBACK,
                EmailTemplateKey.GROUP_SESSION_SCHEDULED_MEMBER,
                EmailTemplateKey.GROUP_SESSION_SCHEDULED_COACH,
                EmailTemplateKey.SESSION_RESCHEDULED)) {
            EmailTemplateRenderer.Rendered rendered =
                    emailTemplates.render(key, EmailTemplateMetadata.sampleValues(key, frontendUrls));
            assertThat(rendered.subject()).isNotBlank().doesNotContain("{{");
            assertThat(rendered.body()).contains("Jordan Lee").doesNotContain("{{");
        }
    }

    /* --------------------------------------------------- audience + tenancy */

    @Test
    @DisplayName("a MEMBERS-scoped module materialises no 1:1 row for the members it excludes, and they leave the roll call")
    void theModuleAudienceBoundsRowsAndTheRollCall() {
        assertThat(bookings.sessionMemberCount(groupSessionId())).isEqualTo(2);

        jdbc.update("UPDATE program_modules SET assign_mode = 'MEMBERS' WHERE id = ?", moduleId);
        jdbc.update("INSERT INTO program_module_members (module_id, user_id) VALUES (?, ?)",
                moduleId, member.getId());
        materializer.sync(cohortId);

        // otherMember never reached the module, so there is no 1:1 row to book,
        // no invite, and no absence to count against them.
        assertThat(unscheduledMemberIds(taskId)).containsExactly(member.getId());
        assertThat(bookings.sessionMemberCount(groupSessionId())).isEqualTo(1);
        assertThat(bookings.sessionMembers(groupSessionId()))
                .singleElement()
                .satisfies(m -> assertThat(m.memberId()).isEqualTo(member.getId()));
    }

    @Test
    @DisplayName("a cohort grant does not show the coach another org's unbooked 1:1 row")
    void anotherOrgsUnbookedOneOnOneStaysOffTheConsole() {
        // A platform cohort two orgs share. The coach's grant is org-wide on
        // org A and reaches the cohort through cohort_orgs, which is enough to
        // DATE the cohort-wide row — but org B's founder is not theirs to see.
        Organization otherOrg = saveOrg("Other Booking Org");
        User foreignMember = new User();
        foreignMember.setEmail("booking.foreign@test.invalid");
        foreignMember.setName("foreign");
        foreignMember.setRole(UserRole.MEMBER);
        foreignMember.setStatus(UserStatus.ACTIVE);
        foreignMember.setOrganization(otherOrg);
        foreignMember = userRepository.saveAndFlush(foreignMember);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                cohortId, otherOrg.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, foreignMember.getId());
        materializer.sync(cohortId);
        assertThat(unscheduledMemberIds(taskId)).contains(foreignMember.getId());

        TestAuthentication.authenticate(coach);
        List<UUID> visible = coachSchedule.sessions().unscheduled().stream()
                .map(CoachSessionDto::memberId).toList();
        assertThat(visible).contains(member.getId(), otherMember.getId())
                .doesNotContain(foreignMember.getId());
    }

    @Test
    @DisplayName("a slot beyond the 8-week horizon is refused even though it sits in published hours")
    void aSlotBeyondTheHorizonIsRefused() {
        TestAuthentication.authenticate(member);
        // 09:00 UTC, so the engine would offer it — only the horizon stops it.
        Instant tooFar = Instant.now().plus(70, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);
        assertThatThrownBy(() -> memberBookings.book(taskId, coach.getId(), tooFar))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("8 weeks ahead");
    }

    @Test
    @DisplayName("the coach's roll call still lands after the job has already completed the session")
    void aRollCallAfterAutoCompletionIsStillAccepted() {
        UUID sessionId = groupSessionId();
        TestAuthentication.authenticate(coach);
        cohortScheduling.schedule(sessionId, null, groupSlots(sessionId).getFirst());
        moveIntoThePast(sessionId);
        assertThat(autoComplete.complete(sessionId)).isTrue();

        // The coach's submit is the authoritative roll call, so the write must
        // still take on a row the job has already flipped to COMPLETED.
        assertThat(bookings.complete(sessionId, Instant.now())).isTrue();
    }

    @Test
    @DisplayName("on a cohort-wide row, feedbackPending is about the READER, not the row's null member")
    void cohortWideFeedbackIsReadPerMember() {
        UUID sessionId = groupSessionId();
        TestAuthentication.authenticate(coach);
        cohortScheduling.schedule(sessionId, null, groupSlots(sessionId).getFirst());
        moveIntoThePast(sessionId);
        assertThat(autoComplete.complete(sessionId)).isTrue();

        jdbc.update("""
                INSERT INTO survey_responses (survey_id, source, respondent_user_id, program_task_id)
                VALUES (?, 'PROGRAM_TASK', ?, ?)
                """, surveyId, member.getId(), groupTaskId);

        TestAuthentication.authenticate(member);
        assertThat(memberBookings.booking(groupTaskId).feedbackPending()).isFalse();
        TestAuthentication.authenticate(otherMember);
        assertThat(memberBookings.booking(groupTaskId).feedbackPending()).isTrue();
    }

    /* ------------------------------------------------------------- seed helpers */

    private UUID saveSessionTask(String name, String sessionType, int position, UUID survey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type,
                                           session_type, ref_id, duration_minutes,
                                           post_session_survey_id)
                VALUES (?, ?, ?, 'LIVE', ?, 'SESSION', ?, NULL, 45, ?)
                """, id, moduleId, name, position, sessionType, survey);
        return id;
    }

    /** The coach's own offer for the cohort-wide session — the member picker is not theirs. */
    private List<Instant> groupSlots(UUID sessionId) {
        Instant now = Instant.now();
        return cohortScheduling.slots(sessionId, null, now, now.plus(7, ChronoUnit.DAYS)).slots();
    }

    private UUID groupSessionId() {
        return jdbc.queryForObject("SELECT id FROM sessions WHERE program_task_id = ?",
                UUID.class, groupTaskId);
    }

    private List<UUID> unscheduledMemberIds(UUID task) {
        return jdbc.queryForList("""
                SELECT member_id FROM sessions
                WHERE program_task_id = ? AND booking_status = 'UNSCHEDULED'
                """, UUID.class, task);
    }

    /** The engine's first offer for the week ahead — always beyond the 12h notice. */
    private Instant firstSlot() {
        List<Instant> slots = slotsForTheNextWeek();
        assertThat(slots).isNotEmpty();
        return slots.getFirst();
    }

    private List<Instant> slotsForTheNextWeek() {
        Instant now = Instant.now();
        return memberBookings.slots(taskId, coach.getId(), now, now.plus(7, ChronoUnit.DAYS)).slots();
    }

    /**
     * Make a session HELD without waiting for the clock. The coach's mark and
     * the auto-complete job are both only legal once the session has ended
     * (spec §6.1, §9), and there is no other way to reach that state in a test.
     */
    private void moveIntoThePast(UUID sessionId) {
        jdbc.update("""
                UPDATE sessions
                SET session_date = now() - interval '2 hours',
                    ends_at      = now() - interval '1 hour'
                WHERE id = ?
                """, sessionId);
    }

    private Organization saveOrg(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setActive(true);
        return organizationRepository.saveAndFlush(organization);
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }
}
