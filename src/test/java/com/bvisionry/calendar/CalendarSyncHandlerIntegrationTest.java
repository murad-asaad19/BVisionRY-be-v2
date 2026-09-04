package com.bvisionry.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.calendar.provider.CalendarProviders;
import com.bvisionry.calendar.provider.GoogleCalendarProvider;
import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.crypto.SecretEncryptionService;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;

/**
 * The sync loop against the real schema: a scheduled session gets its event id
 * and Meet link, a MOVED one keeps the same event, a cancelled one loses both,
 * an unconnected coach is a no-op, and a provider that throws costs the booking
 * nothing.
 *
 * <h2>Why the handler is constructed rather than autowired</h2>
 * The provider has to be a fake — a real Google call is not a test — and
 * swapping it inside the context would either need a second bean claiming the
 * {@code GOOGLE} id (which {@link CalendarProviders} refuses, correctly) or a
 * mock whose {@code id()} is null before stubbing, which breaks context
 * startup. Everything below the handler is the real thing: the real service,
 * the real transactions, the real Postgres.
 *
 * <h2>Why the class is not {@code @Transactional}</h2>
 * The writes under test run {@code REQUIRES_NEW} precisely so they survive
 * independently of the caller, so a rolled-back test transaction could not see
 * them. The fixtures are deleted explicitly instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class CalendarSyncHandlerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CalendarConnectionService connections;
    @Autowired private SecretEncryptionService secrets;
    @Autowired private FrontendUrls frontendUrls;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    private FakeCalendarProvider provider;
    private CalendarSyncHandler handler;

    private Organization org;
    private User coach;
    private UUID cohortId;
    private UUID sessionId;

    @BeforeEach
    void seed() {
        provider = new FakeCalendarProvider();
        handler = new CalendarSyncHandler(
                new CalendarProviders(List.of(provider)), connections, frontendUrls);

        org = new Organization();
        org.setName("Calendar Sync Org");
        org.setActive(true);
        org = organizationRepository.save(org);

        coach = new User();
        coach.setEmail("calendar.sync.coach@test.invalid");
        coach.setName("Sync Coach");
        coach.setRole(UserRole.COACH);
        coach.setStatus(UserStatus.ACTIVE);
        coach.setOrganization(org);
        coach = userRepository.save(coach);

        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Sync Cohort', 'LAUNCHED')",
                cohortId);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohortId, org.getId());

        // A plain (non task-backed) session row: the two columns under test are
        // written by primary key, so the booking shape around them is the coaching
        // slice's business and only complicates this fixture.
        sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sessions (id, cohort_id, type, title, session_date)
                VALUES (?, ?, 'COACHING_GROUP', 'Group coaching', now() + interval '2 days')
                """, sessionId, cohortId);
    }

    @AfterEach
    void cleanUp() {
        // REQUIRES_NEW commits, so nothing here is rolled back for us.
        jdbc.update("DELETE FROM coach_calendar_connections WHERE user_id = ?", coach.getId());
        jdbc.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbc.update("DELETE FROM cohort_orgs WHERE cohort_id = ?", cohortId);
        jdbc.update("DELETE FROM cohorts WHERE id = ?", cohortId);
        userRepository.deleteById(coach.getId());
        organizationRepository.deleteById(org.getId());
    }

    @Test
    @DisplayName("a scheduled cohort session writes the event id and the Meet link onto the row")
    void schedulingWritesTheCalendarColumns() {
        connect();

        handler.onCohortSessionScheduled(cohortScheduled());

        assertThat(column("calendar_event_id")).isEqualTo("evt-1");
        assertThat(column("meeting_url")).isEqualTo("https://meet.google.com/abc-defg-hij");
        assertThat(provider.created).singleElement().satisfies(spec -> {
            assertThat(spec.title()).isEqualTo("Group coaching — Sync Cohort");
            assertThat(spec.requestId()).isEqualTo(sessionId.toString());
            assertThat(spec.attendeeEmails()).containsExactly("founder@test.invalid");
        });
        assertThat(connection().getLastError()).isNull();
        assertThat(connection().getLastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("a 1:1 booking invites the member and carries the same columns")
    void bookingWritesTheCalendarColumns() {
        connect();

        handler.onSessionBooked(new CoachingEvents.SessionBooked(
                sessionId, UUID.randomUUID(), coach.getId(),
                "Alex Founder", "alex@test.invalid",
                coach.getName(), coach.getEmail(), "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T10:45:00Z"),
                UUID.randomUUID(), "Coaching 1:1", cohortId, "Sync Cohort"));

        assertThat(column("calendar_event_id")).isEqualTo("evt-1");
        assertThat(provider.created).singleElement()
                .satisfies(spec -> assertThat(spec.attendeeEmails()).containsExactly("alex@test.invalid"));
    }

    @Test
    @DisplayName("cancelling deletes the provider event and clears both columns")
    void cancellingClearsTheCalendarColumns() {
        connect();
        handler.onCohortSessionScheduled(cohortScheduled());

        handler.onCohortSessionCancelled(new CoachingEvents.CohortSessionCancelled(
                sessionId, coach.getId(), coach.getName(), coach.getEmail(), "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                UUID.randomUUID(), "Group coaching", cohortId, "Sync Cohort",
                List.of(), true));

        assertThat(provider.deleted).containsExactly("evt-1");
        assertThat(column("calendar_event_id")).isNull();
        assertThat(column("meeting_url")).isNull();
    }

    @Test
    @DisplayName("a moved session PATCHES its event instead of losing it to a delete + create")
    void reschedulingUpdatesTheSameEvent() {
        connect();
        handler.onCohortSessionScheduled(cohortScheduled());

        handler.onCohortSessionRescheduled(cohortRescheduled());

        // The event survives: nothing deleted, nothing created a second time.
        assertThat(provider.deleted).isEmpty();
        assertThat(provider.created).hasSize(1);
        assertThat(provider.updated).singleElement().satisfies(spec -> {
            assertThat(spec.startsAt()).isEqualTo(Instant.parse("2026-09-17T14:00:00Z"));
            assertThat(spec.endsAt()).isEqualTo(Instant.parse("2026-09-17T15:00:00Z"));
            assertThat(spec.attendeeEmails()).containsExactly("founder@test.invalid");
        });
        assertThat(column("calendar_event_id")).isEqualTo("evt-1");
        assertThat(column("meeting_url")).isEqualTo("https://meet.google.com/moved-link");
    }

    @Test
    @DisplayName("a move with no event yet CREATES one — the coach connected after the booking")
    void reschedulingWithoutAnEventCreatesOne() {
        connect();

        handler.onCohortSessionRescheduled(cohortRescheduled());

        assertThat(provider.updated).isEmpty();
        assertThat(provider.created).singleElement().satisfies(spec ->
                assertThat(spec.startsAt()).isEqualTo(Instant.parse("2026-09-17T14:00:00Z")));
        assertThat(column("calendar_event_id")).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("an event the coach deleted by hand falls back to a fresh create")
    void aVanishedEventIsRecreated() {
        connect();
        handler.onCohortSessionScheduled(cohortScheduled());
        provider.gone = true;

        handler.onCohortSessionRescheduled(cohortRescheduled());

        assertThat(provider.created).hasSize(2);
        assertThat(column("calendar_event_id")).isEqualTo("evt-1");
        assertThat(connection().getLastError()).isNull();
    }

    @Test
    @DisplayName("a coach with no connected calendar is a no-op, not an error")
    void withoutAConnectionNothingHappens() {
        handler.onCohortSessionScheduled(cohortScheduled());

        assertThat(provider.created).isEmpty();
        assertThat(column("calendar_event_id")).isNull();
    }

    @Test
    @DisplayName("a provider failure is recorded on the connection and never rethrown")
    void aProviderFailureIsRecordedNotThrown() {
        connect();
        provider.failWith = new IllegalStateException("Google is having a day");

        assertThatCode(() -> handler.onCohortSessionScheduled(cohortScheduled()))
                .doesNotThrowAnyException();

        assertThat(column("calendar_event_id")).isNull();
        assertThat(connection().getLastError()).contains("Google is having a day");
        assertThat(connection().getLastSyncedAt()).isNull();
    }

    @Test
    @DisplayName("free/busy comes from the connected calendar, and fails OPEN when it cannot")
    void busyFailsOpen() {
        CalendarBusyAdapter adapter =
                new CalendarBusyAdapter(new CalendarProviders(List.of(provider)), connections);
        Instant from = Instant.parse("2026-09-14T00:00:00Z");
        Instant to = Instant.parse("2026-09-15T00:00:00Z");

        // Not connected: no busy time, no exception — the coach is simply bookable.
        assertThat(adapter.busy(coach.getId(), from, to)).isEmpty();

        connect();
        assertThat(adapter.busy(coach.getId(), from, to))
                .containsExactly(new TimeRange(from, to));

        provider.failWith = new IllegalStateException("freebusy exploded");
        assertThat(adapter.busy(coach.getId(), from, to)).isEmpty();
        assertThat(connection().getLastError()).contains("freebusy exploded");
    }

    @Test
    @DisplayName("Google's callback is reachable with NO session — the whole flow dies otherwise")
    void theCallbackIsAnonymouslyReachable() throws Exception {
        // Google redirects a bare browser here. Two rules have to agree for that to
        // work — the SecurityConfig matcher (the /api/v1/coach/** route demands
        // COACH) and the controller's method-level permitAll (its class demands
        // COACH) — and getting either wrong turns every connect attempt into a
        // silent 403 on Google's redirect, where nobody sees it.
        mockMvc.perform(get("/api/v1/coach/calendar/google/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("calendar=error")));
    }

    @Test
    @DisplayName("the coach's own calendar routes still demand COACH")
    void theOtherRoutesStayClosed() throws Exception {
        mockMvc.perform(get("/api/v1/coach/calendar"))
                .andExpect(status().is(oneOf(401, 403)));
    }

    /* --------------------------------------------------------------- helpers */

    private void connect() {
        connections.connect(coach.getId(), FakeCalendarProvider.ID,
                new CalendarProvider.Grant("refresh-token", "coach@example.com"));
        assertThat(secrets.decrypt(connection().getRefreshTokenEnc())).isEqualTo("refresh-token");
    }

    private CoachingEvents.CohortSessionScheduled cohortScheduled() {
        return new CoachingEvents.CohortSessionScheduled(
                sessionId, coach.getId(), coach.getName(), coach.getEmail(), "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                UUID.randomUUID(), "Group coaching", cohortId, "Sync Cohort",
                List.of(new CoachingEvents.Attendee(
                        UUID.randomUUID(), "Founder One", "founder@test.invalid")));
    }

    private CoachingEvents.CohortSessionRescheduled cohortRescheduled() {
        return new CoachingEvents.CohortSessionRescheduled(
                sessionId, coach.getId(), coach.getName(), coach.getEmail(), "UTC",
                Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"),
                Instant.parse("2026-09-17T14:00:00Z"), Instant.parse("2026-09-17T15:00:00Z"),
                UUID.randomUUID(), "Group coaching", cohortId, "Sync Cohort",
                List.of(new CoachingEvents.Attendee(
                        UUID.randomUUID(), "Founder One", "founder@test.invalid")),
                true);
    }

    private CoachCalendarConnection connection() {
        return connections.find(coach.getId()).orElseThrow();
    }

    private String column(String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM sessions WHERE id = ?",
                String.class, sessionId);
    }

    /** Claims the GOOGLE id so the real provider's bean is simply not involved. */
    private static final class FakeCalendarProvider implements CalendarProvider {

        static final String ID = GoogleCalendarProvider.ID;

        private final List<EventSpec> created = new ArrayList<>();
        private final List<EventSpec> updated = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private RuntimeException failWith;
        /** The coach deleted the event in their own calendar. */
        private boolean gone;

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String authorizationUrl(String state, String redirectUri) {
            return "https://consent.test/?state=" + state;
        }

        @Override
        public Grant exchange(String code, String redirectUri) {
            return new Grant("refresh-token", "coach@example.com");
        }

        @Override
        public CreatedEvent createEvent(CoachCalendarConnection connection, EventSpec spec) {
            if (failWith != null) {
                throw failWith;
            }
            created.add(spec);
            return new CreatedEvent("evt-1", "https://meet.google.com/abc-defg-hij");
        }

        @Override
        public CreatedEvent updateEvent(CoachCalendarConnection connection, String externalId,
                                        EventSpec spec) {
            if (failWith != null) {
                throw failWith;
            }
            if (gone) {
                throw new EventNotFound("gone");
            }
            updated.add(spec);
            return new CreatedEvent(externalId, "https://meet.google.com/moved-link");
        }

        @Override
        public void deleteEvent(CoachCalendarConnection connection, String externalId) {
            if (failWith != null) {
                throw failWith;
            }
            deleted.add(externalId);
        }

        @Override
        public List<TimeRange> busy(CoachCalendarConnection connection, Instant from, Instant to) {
            if (failWith != null) {
                throw failWith;
            }
            return List.of(new TimeRange(from, to));
        }

        @Override
        public void revoke(CoachCalendarConnection connection) {
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}
