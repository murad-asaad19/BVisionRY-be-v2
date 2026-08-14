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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessions + attendance + Engagement Record (redesign spec §4): org-admin
 * session CRUD and roll call (§7b stamped), the participation score computed
 * on read with weight renormalization, and the visibility gates — admin +
 * assigned coach read, admin-only writes, NEVER any member access.
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

            // Member: NEVER — neither door, not even for themselves (spec §4).
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
