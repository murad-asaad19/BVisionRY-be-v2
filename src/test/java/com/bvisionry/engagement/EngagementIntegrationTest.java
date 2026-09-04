package com.bvisionry.engagement;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessions + attendance + Engagement Record (redesign spec §4): org-admin
 * session CRUD and roll call (§7b stamped), the participation score computed
 * on read with weight renormalization, and the visibility gates — admin +
 * assigned coach read, admin-only writes, and a member door that only ever
 * answers with the caller's OWN record.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class EngagementIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User superAdmin;     // SUPER_ADMIN — sessions/participation writes (spec §13)
    private User admin;          // ORG_ADMIN org A
    private User adminB;         // ORG_ADMIN org B
    private User coach;          // COACH org A, granted cohort1
    private User coachUngranted; // COACH org A, no grants
    private User founder;        // MEMBER org A, in cohort1
    private User founderTwo;     // MEMBER org A, joins cohort1 in the expected-attendee tests
    private User founderEmpty;   // MEMBER org A, in cohort2 (nothing seeded)
    private UUID cohort1;
    private UUID cohort2;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Engagement Org A");
        orgB = saveOrg("Engagement Org B");
        superAdmin = TestAuthentication.authenticateAsSuperAdmin(userRepository);
        TestAuthentication.clear();
        admin = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        adminB = saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);
        coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);
        coachUngranted = saveUser("coach.none@test.invalid", UserRole.COACH, orgA);
        founder = saveUser("founder.a@test.invalid", UserRole.MEMBER, orgA);
        founderTwo = saveUser("founder.two@test.invalid", UserRole.MEMBER, orgA);
        founderEmpty = saveUser("founder.empty@test.invalid", UserRole.MEMBER, orgA);

        cohort1 = insertCohort(orgA.getId(), "Cohort One", founder.getId());
        cohort2 = insertCohort(orgA.getId(), "Cohort Two", founderEmpty.getId());
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, orgA.getId(), coach.getId(), cohort1);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /** Org-scoped since §13.7 — a roster and its roll call are founder data. */
    private String sessionsUrl(UUID cohortId) {
        return sessionsUrl(orgA.getId(), cohortId);
    }

    private String sessionsUrl(UUID orgId, UUID cohortId) {
        return "/api/organizations/" + orgId + "/cohorts/" + cohortId + "/sessions";
    }

    private String engagementUrl(User member) {
        return "/api/organizations/" + orgA.getId() + "/members/" + member.getId() + "/engagement";
    }

    @Nested
    class SessionCrud {

        @Test
        void createListUpdateDelete_allDated() throws Exception {
            TestAuthentication.authenticate(superAdmin);

            String body = mockMvc.perform(post(sessionsUrl(cohort1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"WORKSHOP","title":"Pressure cards",
                                     "sessionDate":"2026-08-01T15:00:00Z"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type", is("WORKSHOP")))
                    .andExpect(jsonPath("$.title", is("Pressure cards")))
                    .andExpect(jsonPath("$.sessionDate", is("2026-08-01T15:00:00Z")))
                    // §7b: the create itself is dated.
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andExpect(jsonPath("$.attendance", hasSize(0)))
                    .andReturn().getResponse().getContentAsString();
            UUID sessionId = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

            // List: roster = the cohort's members at read time; roll call unticked.
            mockMvc.perform(get(sessionsUrl(cohort1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(1)))
                    .andExpect(jsonPath("$.members[0].id", is(founder.getId().toString())))
                    .andExpect(jsonPath("$.sessions", hasSize(1)))
                    .andExpect(jsonPath("$.sessions[0].attendance", hasSize(0)));

            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"COACHING_GROUP","title":null,
                                     "sessionDate":"2026-08-02T10:00:00Z"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type", is("COACHING_GROUP")))
                    .andExpect(jsonPath("$.title", nullValue()));

            mockMvc.perform(delete(sessionsUrl(cohort1) + "/" + sessionId))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get(sessionsUrl(cohort1)))
                    .andExpect(jsonPath("$.sessions", hasSize(0)));
        }

        @Test
        void foreignCohortIs404_crossCohortSessionIs404() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(sessionsUrl(UUID.randomUUID())))
                    .andExpect(status().isNotFound());

            // A session created in cohort1 is not addressable through cohort2.
            UUID sessionId = insertSession(cohort1, "WORKSHOP", "-1 day");
            mockMvc.perform(delete(sessionsUrl(cohort2) + "/" + sessionId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class RollCall {

        @Test
        void tickIsStampedIdempotent_untickRemoves() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID sessionId = insertSession(cohort1, "WORKSHOP", "-1 day");

            // Tick: a §7b-stamped presence mark naming who marked it.
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + sessionId + "/attendance/"
                            + founder.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attendance", hasSize(1)))
                    .andExpect(jsonPath("$.attendance[0].memberId",
                            is(founder.getId().toString())))
                    .andExpect(jsonPath("$.attendance[0].markedAt", notNullValue()))
                    .andExpect(jsonPath("$.attendance[0].markedByName", is("Test Super Admin")));

            // Re-tick: idempotent, still one mark.
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + sessionId + "/attendance/"
                            + founder.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attendance", hasSize(1)));

            // Untick: the presence row is gone.
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + sessionId + "/attendance/"
                            + founder.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attendance", hasSize(0)));
        }

        @Test
        void nonCohortMemberCannotBeTicked() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID sessionId = insertSession(cohort1, "WORKSHOP", "-1 day");
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + sessionId + "/attendance/"
                            + founderEmpty.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class SessionAuthz {

        @Test
        void onlyAdminsOfTheOrgTouchSessions() throws Exception {
            UUID sessionId = insertSession(cohort1, "WORKSHOP", "-1 day");
            String tick = sessionsUrl(cohort1) + "/" + sessionId + "/attendance/"
                    + founder.getId();

            // The org's own admin runs their own roll call (spec §13.7)…
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(sessionsUrl(cohort1))).andExpect(status().isOk());
            // …another org's admin cannot, through either org's path.
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(sessionsUrl(cohort1))).andExpect(status().isForbidden());
            mockMvc.perform(get(sessionsUrl(orgB.getId(), cohort1)))
                    .andExpect(status().isNotFound());

            // Coach: read-only via the founder profile — NO session access at all.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(sessionsUrl(cohort1))).andExpect(status().isForbidden());
            mockMvc.perform(put(tick).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isForbidden());

            // Member: never.
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(sessionsUrl(cohort1))).andExpect(status().isForbidden());
            mockMvc.perform(put(tick).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isForbidden());
        }

        /**
         * §13.7 on a platform cohort SHARED by two orgs: the session row is
         * common, but each org sees, edits and deletes only its own founders'
         * slice — one org can never read, overwrite or destroy another's roll
         * call through the shared session.
         */
        @Test
        void sharedCohort_eachOrgTouchesOnlyItsOwnFounderSlice() throws Exception {
            UUID shared = insertCohort(orgA.getId(), "Shared Cohort", founder.getId());
            User founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    shared, orgB.getId());
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    shared, founderB.getId());

            // Org B names its founder expected on the session and marks them present.
            UUID sessionId = insertSession(shared, "WORKSHOP", "-1 day");
            jdbc.update("INSERT INTO session_expected_attendees (session_id, member_id) VALUES (?, ?)",
                    sessionId, founderB.getId());
            jdbc.update("INSERT INTO session_attendance (session_id, member_id, marked_by) VALUES (?, ?, ?)",
                    sessionId, founderB.getId(), adminB.getId());

            // Org A reads the same session and sees NONE of org B's founder data.
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(sessionsUrl(shared)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions", hasSize(1)))
                    .andExpect(jsonPath("$.sessions[0].expectedMemberIds", hasSize(0)))
                    .andExpect(jsonPath("$.sessions[0].attendance", hasSize(0)));

            // Org A adds its OWN founder as expected — must not wipe org B's slice.
            mockMvc.perform(put(sessionsUrl(shared) + "/" + sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"WORKSHOP","sessionDate":"2026-08-02T10:00:00Z",
                                     "expectedMemberIds":["%s"]}
                                    """.formatted(founder.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expectedMemberIds", hasSize(1)))
                    .andExpect(jsonPath("$.expectedMemberIds[0]", is(founder.getId().toString())));

            // Org B still sees its founder expected AND present — untouched.
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(sessionsUrl(orgB.getId(), shared)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[0].expectedMemberIds", hasSize(1)))
                    .andExpect(jsonPath("$.sessions[0].expectedMemberIds[0]",
                            is(founderB.getId().toString())))
                    .andExpect(jsonPath("$.sessions[0].attendance", hasSize(1)));

            // Neither org may delete a session the other participates in (409).
            TestAuthentication.authenticate(admin);
            mockMvc.perform(delete(sessionsUrl(shared) + "/" + sessionId))
                    .andExpect(status().isConflict());
        }
    }

    /**
     * "Bvisionry Labs" — the member-facing schedule. Distinct from the
     * Engagement Record §4 keeps off the member's report: it answers when we
     * meet and whether I turned up, and carries nothing about anyone else.
     */
    @Nested
    class MySessions {

        @Test
        void listsOnlyTheCallersOwnCohortsAndOwnAttendance() throws Exception {
            UUID attended = insertSession(cohort1, "WORKSHOP", "-7 days");
            insertSession(cohort1, "COACHING_GROUP", "+7 days");
            // Another cohort the founder is NOT in.
            UUID otherCohort = insertCohort(orgA.getId(), "Not Mine", founderEmpty.getId());
            insertSession(otherCohort, "WORKSHOP", "-1 day");
            jdbc.update("INSERT INTO session_attendance (session_id, member_id, marked_by) VALUES (?, ?, ?)",
                    attended, founder.getId(), admin.getId());

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/my/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    // Newest first: the future one leads.
                    .andExpect(jsonPath("$[0].type", is("COACHING_GROUP")))
                    .andExpect(jsonPath("$[0].attended", is(false)))
                    .andExpect(jsonPath("$[1].id", is(attended.toString())))
                    .andExpect(jsonPath("$[1].attended", is(true)))
                    .andExpect(jsonPath("$[1].cohortName", is("Cohort One")))
                    // Nobody else's data rides along.
                    .andExpect(jsonPath("$[0].expectedMemberIds").doesNotExist())
                    .andExpect(jsonPath("$[0].attendance").doesNotExist());
        }

        /** A 1:1 that names another founder is not on my schedule. */
        @Test
        void hidesASessionTheCallerIsNotExpectedAt() throws Exception {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohort1, founderTwo.getId());
            UUID oneOnOne = insertSession(cohort1, "COACHING_1ON1", "-1 day");
            jdbc.update("INSERT INTO session_expected_attendees (session_id, member_id) VALUES (?, ?)",
                    oneOnOne, founderTwo.getId());

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/my/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            TestAuthentication.authenticate(founderTwo);
            mockMvc.perform(get("/api/my/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is(oneOnOne.toString())));
        }

        /** A DRAFT cohort is invisible to members everywhere else too. */
        @Test
        void hidesSessionsOfADraftCohort() throws Exception {
            UUID draft = insertCohort(orgA.getId(), "Unlaunched", founder.getId());
            jdbc.update("UPDATE cohorts SET status = 'DRAFT' WHERE id = ?", draft);
            insertSession(draft, "WORKSHOP", "-1 day");

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/my/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    class ExpectedAttendees {

        /**
         * A 1:1 names its one founder (spec §4 / prototype `expected: 1`):
         * it sits in that founder's coaching_1on1 denominator and history,
         * and in NOBODY else's — while a workshop with no narrowing still
         * counts for the whole cohort.
         */
        @Test
        void oneOnOneCountsOnlyForItsExpectedFounder() throws Exception {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohort1, founderTwo.getId());
            TestAuthentication.authenticate(superAdmin);

            // Held 1:1 for founder only, created through the API (round-trip).
            String body = mockMvc.perform(post(sessionsUrl(cohort1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"COACHING_1ON1","title":"1:1 — founder.a",
                                     "sessionDate":"%s",
                                     "expectedMemberIds":["%s"]}
                                    """.formatted(Instant.now().minus(1, ChronoUnit.DAYS),
                                    founder.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.expectedCount", is(1)))
                    .andExpect(jsonPath("$.expectedMemberIds",
                            is(java.util.List.of(founder.getId().toString()))))
                    .andReturn().getResponse().getContentAsString();
            UUID oneOnOne = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + oneOnOne + "/attendance/"
                            + founder.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isOk());

            // Held workshop with NO narrowing — whole cohort expected.
            insertSession(cohort1, "WORKSHOP", "-2 days");

            // founder: 1:1 counts 1/1 and shows in history.
            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].key",
                            is("coaching_1on1")))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].done", is(1)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].total", is(1)))
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(2)));

            // founderTwo: the 1:1 is in NEITHER their denominator NOR their
            // history; the un-narrowed workshop still counts for them.
            mockMvc.perform(get(engagementUrl(founderTwo)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].total", is(0)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].key",
                            is("workshops")))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].total", is(1)))
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(1)))
                    .andExpect(jsonPath("$.cohorts[0].sessions[0].type", is("WORKSHOP")));
        }

        /**
         * A session is HELD when it has ENDED, not when it has started. A
         * workshop running right now would otherwise land in the member's
         * denominator as an absence for the length of the session — until the
         * auto-complete job catches up.
         */
        @Test
        void aSessionStillInProgressIsNotYetHeld() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID inProgress = UUID.randomUUID();
            // Task-backed and SCHEDULED: the only shape V216 lets carry an
            // ends_at, and the only one that can be mid-session at all.
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, session_date, ends_at,
                                          program_task_id, coach_id, booking_status, created_by)
                    VALUES (?, ?, 'WORKSHOP', 'Running right now', now() - interval '10 minutes',
                            now() + interval '50 minutes', ?, ?, 'SCHEDULED', ?)
                    """, inProgress, cohort1, UUID.randomUUID(), admin.getId(), admin.getId());

            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].key",
                            is("workshops")))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].total", is(0)))
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(0)));

            // Once it has ended it counts — as a miss, since nobody was marked.
            jdbc.update("UPDATE sessions SET ends_at = now() - interval '1 minute' WHERE id = ?",
                    inProgress);
            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].total", is(1)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].done", is(0)))
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(1)));
        }

        /**
         * V207: pillar tags round-trip on the upsert, and only the cohort's
         * fully-mapped distance pillars are taggable — the board task save's
         * rule, restated here.
         */
        @Test
        void pillarTagsRoundTrip_andOnlyMappedPairsAreTaggable() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID mapped = insertMappedPillarPair(cohort1, "Vision");

            mockMvc.perform(post(sessionsUrl(cohort1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"WORKSHOP","title":"Vision lab",
                                     "sessionDate":"2026-08-01T15:00:00Z",
                                     "pillarIds":["%s"]}
                                    """.formatted(mapped)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pillarIds", hasSize(1)))
                    .andExpect(jsonPath("$.pillarIds[0]", is(mapped.toString())));

            // An id that is no cohort pair feeds no narrative — rejected.
            mockMvc.perform(post(sessionsUrl(cohort1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"WORKSHOP",
                                     "sessionDate":"2026-08-01T15:00:00Z",
                                     "pillarIds":["%s"]}
                                    """.formatted(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Sessions spec v2 §6.4: a task-backed row IS a session row, so the tab
         * lists it (with its coach, status and Meet link) but never edits or
         * deletes it — the coach manages it from their own console.
         */
        @Test
        void aTaskBackedSessionIsListedButReadOnly() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID memberId = jdbc.queryForObject(
                    "SELECT user_id FROM cohort_members WHERE cohort_id = ? LIMIT 1",
                    UUID.class, cohort1);
            UUID taskId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, session_date, ends_at,
                                          program_task_id, member_id, coach_id, booking_status,
                                          meeting_url)
                    VALUES (?, ?, 'COACHING_1ON1', 'Coaching 1:1', now() + interval '2 days',
                            now() + interval '2 days 45 minutes', ?, ?, ?, 'SCHEDULED',
                            'https://meet.google.com/abc-defg-hij')
                    """, bookingId, cohort1, taskId, memberId, admin.getId());
            jdbc.update("INSERT INTO session_expected_attendees (session_id, member_id) VALUES (?, ?)",
                    bookingId, memberId);

            mockMvc.perform(get(sessionsUrl(cohort1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].bookingStatus"
                            .formatted(bookingId), contains("SCHEDULED")))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].coachName"
                            .formatted(bookingId), contains(admin.getName())))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].memberId"
                            .formatted(bookingId), contains(memberId.toString())))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].programTaskId"
                            .formatted(bookingId), contains(taskId.toString())))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].meetingUrl"
                            .formatted(bookingId), contains("https://meet.google.com/abc-defg-hij")));

            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + bookingId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"COACHING_1ON1","sessionDate":"2026-08-01T15:00:00Z"}
                                    """))
                    .andExpect(status().isConflict());
            mockMvc.perform(delete(sessionsUrl(cohort1) + "/" + bookingId))
                    .andExpect(status().isConflict());
            // Roll call on a booking stays the coach's too: it decides whether
            // the task is done and whether the follow-up survey opens, so the
            // org tab must not be a second door into it.
            mockMvc.perform(put(sessionsUrl(cohort1) + "/" + bookingId + "/attendance/" + memberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"present\":true}"))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM session_attendance WHERE session_id = ?",
                    Integer.class, bookingId)).isZero();
        }

        /**
         * Sessions spec v2 §2/§6.4: a task-backed row exists before anyone
         * dates it. It lists with a null date, sorts BELOW the dated ones
         * (a plain {@code ORDER BY session_date DESC} would put those NULLs
         * on top), and stays read-only like every other task-backed row.
         */
        /**
         * Sessions spec v2 §2: a 1:1 row is ABOUT one founder. On a cohort two
         * orgs share, the org that does not have that founder must not see the
         * row at all — not even as an anonymous "0 of 1 attended" line the UI
         * could mistake for a cohort-wide session. Cohort-wide rows stay shared.
         */
        @Test
        void anotherOrgsOneOnOneRowIsInvisibleHere() throws Exception {
            UUID shared = insertCohort(orgA.getId(), "Shared Cohort", founder.getId());
            User founderB = saveUser("founder.b2@test.invalid", UserRole.MEMBER, orgB);
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    shared, orgB.getId());
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    shared, founderB.getId());
            UUID taskId = UUID.randomUUID();
            UUID oneOnOneB = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, program_task_id, member_id,
                                          booking_status)
                    VALUES (?, ?, 'COACHING_1ON1', 'Coaching 1:1', ?, ?, 'UNSCHEDULED')
                    """, oneOnOneB, shared, taskId, founderB.getId());
            UUID groupRow = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, program_task_id,
                                          booking_status)
                    VALUES (?, ?, 'COACHING_GROUP', 'Group coaching', ?, 'UNSCHEDULED')
                    """, groupRow, shared, UUID.randomUUID());

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(sessionsUrl(shared)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')]".formatted(oneOnOneB), empty()))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')]".formatted(groupRow), hasSize(1)));

            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(sessionsUrl(orgB.getId(), shared)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].memberId".formatted(oneOnOneB),
                            contains(founderB.getId().toString())));
        }

        @Test
        void anUnscheduledSessionListsUndatedAndLast() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            UUID unscheduledId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, program_task_id,
                                          booking_status)
                    VALUES (?, ?, 'COACHING_GROUP', 'Group coaching', ?, 'UNSCHEDULED')
                    """, unscheduledId, cohort1, UUID.randomUUID());
            UUID datedId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sessions (id, cohort_id, type, title, session_date)
                    VALUES (?, ?, 'WORKSHOP', 'Dated workshop', now() - interval '1 day')
                    """, datedId, cohort1);

            mockMvc.perform(get(sessionsUrl(cohort1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].sessionDate"
                            .formatted(unscheduledId), contains(nullValue())))
                    .andExpect(jsonPath("$.sessions[?(@.id == '%s')].bookingStatus"
                            .formatted(unscheduledId), contains("UNSCHEDULED")))
                    // …and it is the LAST row, below every dated session.
                    .andExpect(jsonPath("$.sessions[-1:].id", contains(unscheduledId.toString())));

            mockMvc.perform(delete(sessionsUrl(cohort1) + "/" + unscheduledId))
                    .andExpect(status().isConflict());
        }

        @Test
        void expectedAttendeesMustBeCohortMembers() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(post(sessionsUrl(cohort1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"COACHING_1ON1",
                                     "sessionDate":"2026-08-01T15:00:00Z",
                                     "expectedMemberIds":["%s"]}
                                    """.formatted(founderEmpty.getId())))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class ParticipationRead {

        /**
         * Assignments 2/3 (two LIVE program tasks, one submitted, plus one
         * submitted exercise), workshops 1/2 held (a future workshop excluded
         * from the denominator), zero coaching sessions → the coaching weights
         * (15+10) renormalize away: (66.67×50 + 50×25) / 75 = 61.1, Partial.
         */
        @Test
        void participationComputesWithRenormalization() throws Exception {
            seedAssignmentsForFounder();
            UUID attended = insertSession(cohort1, "WORKSHOP", "-7 days");
            insertSession(cohort1, "WORKSHOP", "-1 day");
            insertSession(cohort1, "WORKSHOP", "+7 days"); // future — excluded
            jdbc.update("""
                    INSERT INTO session_attendance (session_id, member_id, marked_by)
                    VALUES (?, ?, ?)
                    """, attended, founder.getId(), admin.getId());

            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts", hasSize(1)))
                    .andExpect(jsonPath("$.cohorts[0].cohortName", is("Cohort One")))
                    .andExpect(jsonPath("$.cohorts[0].participation.score", is(61.1)))
                    .andExpect(jsonPath("$.cohorts[0].participation.bandKey", is("band_2")))
                    .andExpect(jsonPath("$.cohorts[0].participation.bandLabel", is("Partial")))
                    .andExpect(jsonPath("$.cohorts[0].participation.computedAt", notNullValue()))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories", hasSize(4)))
                    // assignments: submitted ÷ assigned, renormalized 50→66.7
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[0].key",
                            is("assignments")))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[0].done", is(2)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[0].total", is(3)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[0].effectiveWeight",
                            is(66.7)))
                    // workshops: attended ÷ held (future session excluded)
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].done", is(1)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[1].total", is(2)))
                    // coaching categories: no denominator → excluded, weight kept visible
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].total", is(0)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].pct",
                            nullValue()))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[2].weight", is(15)))
                    // §7b: the attendance history is stamped.
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(2)))
                    .andExpect(jsonPath("$.cohorts[0].sessions[1].attended", is(true)))
                    .andExpect(jsonPath("$.cohorts[0].sessions[1].markedAt", notNullValue()))
                    .andExpect(jsonPath("$.cohorts[0].sessions[0].attended", is(false)))
                    .andExpect(jsonPath("$.cohorts[0].sessions[0].markedAt", nullValue()));
        }

        @Test
        void emptyCohortHasNoScoreNotZero() throws Exception {
            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(engagementUrl(founderEmpty)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts", hasSize(1)))
                    .andExpect(jsonPath("$.cohorts[0].participation.score", nullValue()))
                    .andExpect(jsonPath("$.cohorts[0].participation.bandKey", nullValue()))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories", hasSize(4)))
                    .andExpect(jsonPath("$.cohorts[0].sessions", hasSize(0)));
        }

        @Test
        void visibilityIsAdminAndAssignedCoachOnly() throws Exception {
            // Assigned coach: same payload through the coach door.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/engagement"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts[0].cohortName", is("Cohort One")));

            // Ungranted coach: 404, no leak.
            TestAuthentication.authenticate(coachUngranted);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/engagement"))
                    .andExpect(status().isNotFound());

            // Foreign admin: rejected at the org gate.
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isForbidden());

            // Member: never through the ADMIN or COACH door, not even for
            // themselves — their own record has its own door (see below).
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(engagementUrl(founder)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/engagement"))
                    .andExpect(status().isForbidden());

            // Foreign member id under my org path → 404.
            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + UUID.randomUUID() + "/engagement"))
                    .andExpect(status().isNotFound());
        }

        /**
         * The member's own door (operator decision 2026-08-19) — the same
         * record the admin reads for THIS founder, carrying no id to widen. A
         * caller with no learner row (staff opening the member surface) gets an
         * empty record rather than a 404.
         */
        @Test
        void memberReadsTheirOwnRecordThroughTheirOwnDoor() throws Exception {
            seedAssignmentsForFounder();

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/my/engagement"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts", hasSize(1)))
                    .andExpect(jsonPath("$.cohorts[0].cohortName", is("Cohort One")))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories", hasSize(4)))
                    .andExpect(jsonPath("$.cohorts[0].participation.categories[0].done", is(2)));

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get("/api/my/engagement"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohorts", hasSize(0)));
        }
    }

    /* ------------------------------------------------- Pulse participation */

    /**
     * Spec §4 names Pulse as a participation surface. One request for the whole
     * roster, scoring each founder through the SAME code the per-member record
     * uses — so the Pulse column and the founder profile can never disagree.
     */
    @Nested
    class CohortParticipationRead {

        /** Org-scoped since §13.7 — participation is founder progress, i.e. org data. */
        private String url(UUID cohortId) {
            return url(orgA.getId(), cohortId);
        }

        private String url(UUID orgId, UUID cohortId) {
            return "/api/organizations/" + orgId + "/cohorts/" + cohortId + "/participation";
        }

        @Test
        void rosterScoresMatchThePerMemberRecord_andAverageSkipsUnscoredFounders()
                throws Exception {
            seedAssignmentsForFounder();
            UUID attended = insertSession(cohort1, "WORKSHOP", "-7 days");
            insertSession(cohort1, "WORKSHOP", "-1 day");
            jdbc.update("""
                    INSERT INTO session_attendance (session_id, member_id, marked_by)
                    VALUES (?, ?, ?)
                    """, attended, founder.getId(), admin.getId());

            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(url(cohort1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(1)))
                    .andExpect(jsonPath("$.members[0].memberId", is(founder.getId().toString())))
                    // Identical to the per-member record's number for this founder.
                    .andExpect(jsonPath("$.members[0].participation.score", is(61.1)))
                    .andExpect(jsonPath("$.members[0].participation.bandLabel", is("Partial")))
                    .andExpect(jsonPath("$.average", is(61.1)));

            // A founder with no denominator scores null, so the average has
            // nothing to average — null, never a misleading 0.
            mockMvc.perform(get(url(cohort2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(1)))
                    .andExpect(jsonPath("$.members[0].participation.score", nullValue()))
                    .andExpect(jsonPath("$.average", nullValue()));
        }

        @Test
        void adminsOfTheOrgOnly_andAnUnassignedCohortIs404() throws Exception {
            // The org's own admin reads their own slice (spec §13.7).
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(url(cohort1))).andExpect(status().isOk());
            // Another org's admin cannot — not even through org A's path.
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(url(cohort1))).andExpect(status().isForbidden());
            // …nor by pointing their OWN org at a cohort that is not assigned to it.
            mockMvc.perform(get(url(orgB.getId(), cohort1))).andExpect(status().isNotFound());

            // Participation is never member-facing (spec §4), not even the coach door.
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(url(cohort1))).andExpect(status().isForbidden());
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(url(cohort1))).andExpect(status().isForbidden());

            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(url(UUID.randomUUID()))).andExpect(status().isNotFound());
        }
    }

    /* ------------------------------------------------- engagement report */

    /**
     * The Engagement tab as a file — same numbers, same gate, plus the
     * {@code showNames} authority ({@code ExportNameGuard}): masked positional
     * labels by default, real names only when an org admin or super admin asks.
     * Assertions read the BYTES the caller receives, like
     * {@code ExportNameAuthorityIntegrationTest}.
     */
    @Nested
    class EngagementReportExports {

        private String url(UUID orgId, UUID cohortId, String ext) {
            return "/api/organizations/" + orgId + "/cohorts/" + cohortId
                    + "/engagement-report." + ext;
        }

        private byte[] ok(String route) throws Exception {
            return mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
        }

        private void seedParticipation() {
            seedAssignmentsForFounder();
            UUID attended = insertSession(cohort1, "WORKSHOP", "-7 days");
            insertSession(cohort1, "WORKSHOP", "-1 day");
            jdbc.update("""
                    INSERT INTO session_attendance (session_id, member_id, marked_by)
                    VALUES (?, ?, ?)
                    """, attended, founder.getId(), admin.getId());
        }

        @Test
        void maskedByDefault_onTheBytes() throws Exception {
            seedParticipation();
            TestAuthentication.authenticate(admin);

            String pdf = pdfText(ok(url(orgA.getId(), cohort1, "pdf")));
            assertThat(pdf).doesNotContain("founder.a").contains("Member 1")
                    .contains("Cohort One");

            String cells = cellText(ok(url(orgA.getId(), cohort1, "xlsx")));
            assertThat(cells).doesNotContain("founder.a").contains("Member 1");
        }

        @Test
        void orgAdminAskingForNamesGetsThem_andTheNumbersMatchTheTab() throws Exception {
            seedParticipation();
            TestAuthentication.authenticate(admin);

            assertThat(pdfText(ok(url(orgA.getId(), cohort1, "pdf") + "?showNames=true")))
                    .contains("founder.a");

            byte[] xlsx = ok(url(orgA.getId(), cohort1, "xlsx") + "?showNames=true");
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                Sheet sheet = wb.getSheetAt(0);
                assertThat(sheet.getSheetName()).isEqualTo("Engagement");
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Founder");
                Row row = sheet.getRow(1);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo("founder.a");
                // 4 config categories × (done, total, %) after the name, then
                // score + band — the same 61.1 / Partial the JSON read serves.
                assertThat(row.getCell(13).getNumericCellValue()).isEqualTo(61.1);
                assertThat(row.getCell(14).getStringCellValue()).isEqualTo("Partial");
                // The average footer.
                assertThat(sheet.getRow(2).getCell(0).getStringCellValue())
                        .isEqualTo("Cohort average");
                assertThat(sheet.getRow(2).getCell(13).getNumericCellValue()).isEqualTo(61.1);
            }
        }

        @Test
        void previewIsInline_downloadIsAttachment_namedAfterTheCohort() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(url(orgA.getId(), cohort1, "pdf")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"Engagement_Report_Cohort_One.pdf\""));
            mockMvc.perform(get(url(orgA.getId(), cohort1, "pdf") + "?mode=preview"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "inline; filename=\"Engagement_Report_Cohort_One.pdf\""));
            mockMvc.perform(get(url(orgA.getId(), cohort1, "xlsx") + "?mode=preview"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "inline; filename=\"Engagement_Report_Cohort_One.xlsx\""));
        }

        @Test
        void coachMemberAndForeignAdminAreDenied() throws Exception {
            for (User denied : java.util.List.of(coach, founder, adminB)) {
                TestAuthentication.authenticate(denied);
                mockMvc.perform(get(url(orgA.getId(), cohort1, "pdf")))
                        .andExpect(status().isForbidden());
                mockMvc.perform(get(url(orgA.getId(), cohort1, "xlsx")))
                        .andExpect(status().isForbidden());
            }
        }

        /** Pointing your own org at a cohort not assigned to it is a 404, not a leak. */
        @Test
        void foreignCohortReadsAsAbsent() throws Exception {
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(url(orgB.getId(), cohort1, "pdf")))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(url(orgB.getId(), cohort1, "xlsx")))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(superAdmin);
            mockMvc.perform(get(url(orgA.getId(), UUID.randomUUID(), "pdf")))
                    .andExpect(status().isNotFound());
        }

        /** The rendered text of the PDF, decompressed — a raw byte search sees nothing. */
        private String pdfText(byte[] pdf) throws Exception {
            PdfReader reader = new PdfReader(pdf);
            try {
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                StringBuilder text = new StringBuilder();
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    text.append(extractor.getTextFromPage(page)).append('\n');
                }
                return text.toString();
            } finally {
                reader.close();
            }
        }

        /** Every string cell in the workbook — the readable content of the export. */
        private String cellText(byte[] xlsx) throws Exception {
            StringBuilder out = new StringBuilder();
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                for (Sheet sheet : wb) {
                    for (Row row : sheet) {
                        for (Cell cell : row) {
                            if (cell.getCellType() == CellType.STRING) {
                                out.append(cell.getStringCellValue()).append('\n');
                            }
                        }
                    }
                }
            }
            return out.toString();
        }
    }

    /* ------------------------------------------------------------ seeding */

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.saveAndFlush(org);
    }

    private User saveUser(String email, UserRole role, Organization org) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }

    private UUID insertCohort(UUID orgId, String name, UUID memberId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')", id, name);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", id, orgId);
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", id, memberId);
        return id;
    }

    /** A fully-mapped baseline↔distance pair for the cohort; returns the distance pillar id. */
    private UUID insertMappedPillarPair(UUID cohortId, String name) {
        UUID baselinePipeline = insertPipeline(name + " baseline");
        UUID distancePipeline = insertPipeline(name + " distance");
        UUID baselinePillar = insertPillar(baselinePipeline, name);
        UUID distancePillar = insertPillar(distancePipeline, name);
        jdbc.update("""
                INSERT INTO comparison_pillar_mappings
                    (cohort_id, baseline_pipeline_id, distance_pipeline_id,
                     baseline_pillar_id, distance_pillar_id, source)
                VALUES (?, ?, ?, ?, ?, 'AUTO')
                """, cohortId, baselinePipeline, distancePipeline, baselinePillar, distancePillar);
        return distancePillar;
    }

    private UUID insertPipeline(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                id, name, admin.getId());
        return id;
    }

    private UUID insertPillar(UUID pipelineId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, 0)",
                id, pipelineId, name);
        return id;
    }

    /** A session offset from now, e.g. {@code "-1 day"} (held) or {@code "+7 days"} (future). */
    private UUID insertSession(UUID cohortId, String type, String offset) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sessions (id, cohort_id, type, session_date, created_by)
                VALUES (?, ?, ?, now() + (?::interval), ?)
                """, id, cohortId, type, offset, admin.getId());
        return id;
    }

    /** Two LIVE program tasks (one SUBMITTED) + one submitted exercise → 2/3. */
    private void seedAssignmentsForFounder() {
        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 'ALL', 'UNLOCKED')
                """, moduleId, cohort1);
        UUID task1 = UUID.randomUUID();
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, 'Task One', 'LIVE')",
                task1, moduleId);
        jdbc.update("INSERT INTO program_tasks (module_id, name, status) VALUES (?, 'Task Two', 'LIVE')",
                moduleId);
        // DRAFT tasks are not "assigned" — must not count in the denominator.
        jdbc.update("INSERT INTO program_tasks (module_id, name, status) VALUES (?, 'Draft Task', 'DRAFT')",
                moduleId);
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, task1, founder.getId());

        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Competitor Analysis', 'PUBLISHED', ?)
                """, templateId, admin.getId());
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, templateId, orgA.getId(), founder.getId(), admin.getId());
        jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, assignmentId, founder.getId());
    }
}
