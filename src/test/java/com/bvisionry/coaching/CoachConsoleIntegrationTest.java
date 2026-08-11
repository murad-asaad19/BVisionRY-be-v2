package com.bvisionry.coaching;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The coach console's tenancy story, end to end against the real schema:
 *
 * <ul>
 *   <li>visibility is the UNION of cohort grants and direct grants;</li>
 *   <li>a founder outside the union — same org or another tenant — is a 404
 *       at the data layer, not a hidden nav entry;</li>
 *   <li>MEMBER / INSTRUCTOR / ORG_ADMIN / foreign callers never clear the
 *       console's gates;</li>
 *   <li>org-admin grant management resolves every referenced row with the org
 *       predicate;</li>
 *   <li>the exercise review loop admits a coach only inside their union;</li>
 *   <li>a coach publishes a Cal.com booking link on their OWN row only, and
 *       only for a cal.com host;</li>
 *   <li>the founder's side is the same union read backwards — they see exactly
 *       the coaches who can see them.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CoachConsoleIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The founder's side of the relationship — identity is the scope, no id in the path. */
    private static final String MY_COACHES = "/api/v1/me/coaches";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User coach;       // COACH in org A: cohort1 + direct(founderDirect)
    private User coachB;      // COACH in org B
    private User founderInCohort;   // org A, member of cohort1 AND cohort2 -> visible via the cohort1 grant
    private User founderOtherCohort; // org A, member of cohort2 only -> NOT visible
    private User founderDirect;      // org A, no cohort          -> visible (direct grant)
    private User founderUnassigned;  // org A, no cohort, no grant -> NOT visible
    private User founderB;           // org B, member of cohortB   -> visible to coachB only
    private UUID cohort1;
    private UUID cohort2;
    private UUID cohortB;
    private UUID exerciseAssignmentVisible;
    private UUID exerciseAssignmentHidden;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Coach Org A");
        orgB = saveOrg("Coach Org B");

        coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);
        coachB = saveUser("coach.b@test.invalid", UserRole.COACH, orgB);
        founderInCohort = saveUser("founder.cohort@test.invalid", UserRole.MEMBER, orgA);
        founderOtherCohort = saveUser("founder.other@test.invalid", UserRole.MEMBER, orgA);
        founderDirect = saveUser("founder.direct@test.invalid", UserRole.MEMBER, orgA);
        founderUnassigned = saveUser("founder.unassigned@test.invalid", UserRole.MEMBER, orgA);
        founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);

        cohort1 = insertCohort(orgA.getId(), "Cohort One", founderInCohort.getId());
        cohort2 = insertCohort(orgA.getId(), "Cohort Two", founderOtherCohort.getId());
        cohortB = insertCohort(orgB.getId(), "Cohort B", founderB.getId());
        // The grain seam: the visible founder ALSO belongs to the UNGRANTED
        // cohort2 — its name, modules and progress must never leak through a
        // cohort1-grain grant.
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohort2, founderInCohort.getId());

        insertGrant(orgA.getId(), coach.getId(), cohort1, null);
        insertGrant(orgA.getId(), coach.getId(), null, founderDirect.getId());
        insertGrant(orgB.getId(), coachB.getId(), cohortB, null);

        seedProgram();
        seedPillarScores();
        seedExercises();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------------------- console */

    @Nested
    class Roster {

        @Test
        void unionOfCohortAndDirectGrants_nothingElse() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/roster"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founders", hasSize(2)))
                    .andExpect(jsonPath("$.founders[0].name", is("founder.cohort")))
                    // No email anywhere — coach_sees does not include contact data.
                    .andExpect(jsonPath("$.founders[0].email").doesNotExist())
                    // GRAIN: the founder is also in the ungranted Cohort Two —
                    // its name and its task must not leak through a cohort1 grant.
                    .andExpect(jsonPath("$.founders[0].cohortNames", is("Cohort One")))
                    .andExpect(jsonPath("$.founders[0].totalTasks", is(2)))
                    .andExpect(jsonPath("$.founders[0].submittedTasks", is(1)))
                    .andExpect(jsonPath("$.founders[0].completionPct", is(50)))
                    .andExpect(jsonPath("$.founders[1].name", is("founder.direct")))
                    .andExpect(jsonPath("$.founders[1].totalTasks", is(0)))
                    .andExpect(jsonPath("$.founders[1].completionPct", nullValue()));
        }

        @Test
        void foreignCoachSeesOnlyTheirOwnOrg() throws Exception {
            TestAuthentication.authenticate(coachB);
            mockMvc.perform(get("/api/v1/coach/roster"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founders", hasSize(1)))
                    .andExpect(jsonPath("$.founders[0].name", is("founder.b")));
        }

        @Test
        void suspendedFounderDropsOutOfRosterAndReview() throws Exception {
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                    founderInCohort.getId());
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/roster"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founders", hasSize(1)))
                    .andExpect(jsonPath("$.founders[0].name", is("founder.direct")));
            mockMvc.perform(get("/api/v1/coach/founders/" + founderInCohort.getId()))
                    .andExpect(status().isNotFound());
            // The review loop uses the SAME shared predicate — no divergence.
            mockMvc.perform(get(review(orgA, exerciseAssignmentVisible)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void nonCoachRolesAreRejectedAtTheGate() throws Exception {
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get("/api/v1/coach/roster")).andExpect(status().isForbidden());

            TestAuthentication.authenticate(saveUser("instructor@test.invalid",
                    UserRole.INSTRUCTOR, orgA));
            mockMvc.perform(get("/api/v1/coach/roster")).andExpect(status().isForbidden());

            TestAuthentication.authenticate(saveUser("orgadmin.a@test.invalid",
                    UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get("/api/v1/coach/roster")).andExpect(status().isForbidden());
        }
    }

    @Nested
    class FounderDetail {

        @Test
        void visibleFounderCarriesScoresProgressAndSubmissions() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founderInCohort.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founder.name", is("founder.cohort")))
                    .andExpect(jsonPath("$.founder.email").doesNotExist())
                    // GRAIN: a cohort1 grant scopes everything journey-shaped
                    // to cohort1 — the ungranted Cohort Two contributes no
                    // name, no module row, no task counts.
                    .andExpect(jsonPath("$.founder.cohortNames", is("Cohort One")))
                    .andExpect(jsonPath("$.founder.totalTasks", is(2)))
                    .andExpect(jsonPath("$.founder.submittedTasks", is(1)))
                    .andExpect(jsonPath("$.pillarScores", hasSize(1)))
                    .andExpect(jsonPath("$.pillarScores[0].pillarName", is("Vision")))
                    .andExpect(jsonPath("$.pillarScores[0].scorePercentage", is(72.5)))
                    .andExpect(jsonPath("$.pillarScores[0].maturityLabel", is("Strong")))
                    .andExpect(jsonPath("$.modules", hasSize(1)))
                    .andExpect(jsonPath("$.modules[0].moduleName", is("Module One")))
                    .andExpect(jsonPath("$.modules[0].cohortName", is("Cohort One")))
                    .andExpect(jsonPath("$.modules[0].totalTasks", is(2)))
                    .andExpect(jsonPath("$.modules[0].submittedTasks", is(1)))
                    .andExpect(jsonPath("$.exercises", hasSize(1)))
                    .andExpect(jsonPath("$.exercises[0].exerciseName", is("Coach Test Exercise")))
                    .andExpect(jsonPath("$.exercises[0].status", is("SUBMITTED")));
        }

        @Test
        void directGrantExposesTheFullJourney() throws Exception {
            // The admin authorized the PERSON: a direct grant reaches the
            // founder's whole journey — both cohorts, all three live tasks.
            User coachDirect = saveUser("coach.direct@test.invalid", UserRole.COACH, orgA);
            insertGrant(orgA.getId(), coachDirect.getId(), null, founderInCohort.getId());
            TestAuthentication.authenticate(coachDirect);
            mockMvc.perform(get("/api/v1/coach/founders/" + founderInCohort.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founder.cohortNames", is("Cohort One, Cohort Two")))
                    .andExpect(jsonPath("$.founder.totalTasks", is(3)))
                    .andExpect(jsonPath("$.founder.submittedTasks", is(2)))
                    .andExpect(jsonPath("$.founder.completionPct", is(67)))
                    .andExpect(jsonPath("$.modules", hasSize(2)))
                    .andExpect(jsonPath("$.modules[0].moduleName", is("Module One")))
                    .andExpect(jsonPath("$.modules[1].moduleName", is("Module Two")));
        }

        @Test
        void holdingBothGrainsIsTheFullUnion() throws Exception {
            insertGrant(orgA.getId(), coach.getId(), null, founderInCohort.getId());
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founderInCohort.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founder.cohortNames", is("Cohort One, Cohort Two")))
                    .andExpect(jsonPath("$.founder.totalTasks", is(3)))
                    .andExpect(jsonPath("$.founder.submittedTasks", is(2)))
                    .andExpect(jsonPath("$.modules", hasSize(2)));
        }

        @Test
        void founderOutsideTheUnionIs404_evenInTheCoachsOwnOrg() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founderOtherCohort.getId()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/coach/founders/" + founderUnassigned.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anotherTenantsFounderIs404() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founderB.getId()))
                    .andExpect(status().isNotFound());
        }
    }

    /* -------------------------------------- booking profile (calendar_integration) */

    /**
     * The write side of the Cal.com integration. Two rules carry it, and both
     * are asserted by mutation rather than by inspection: the host allowlist
     * (only https cal.com / *.cal.com, dot-boundary) and "a coach writes only
     * their own row" (structural — the PK is the principal, the request names
     * no id).
     */
    @Nested
    class BookingProfile {

        @Test
        void coachPublishesAndWithdrawsTheirCalComLink() throws Exception {
            TestAuthentication.authenticate(coach);

            // Nothing published yet is a legitimate state, not a 404.
            mockMvc.perform(get("/api/v1/coach/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingUrl", nullValue()));

            mockMvc.perform(patchProfile("https://cal.com/coach-a/intro"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingUrl", is("https://cal.com/coach-a/intro")));
            mockMvc.perform(get("/api/v1/coach/profile"))
                    .andExpect(jsonPath("$.bookingUrl", is("https://cal.com/coach-a/intro")));

            // A subdomain is the same provider; the update replaces, never appends.
            mockMvc.perform(patchProfile("https://app.cal.com/coach-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingUrl", is("https://app.cal.com/coach-a")));

            // Blank withdraws it — one representation of "cleared" in the column.
            mockMvc.perform(patchProfile("   "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingUrl", nullValue()));
            assertThat(jdbc.queryForObject(
                    "SELECT booking_url FROM coach_profiles WHERE coach_id = ?",
                    String.class, coach.getId())).isNull();
        }

        /**
         * The allowlist. Every rejected value here is a way past a
         * {@code contains}/{@code endsWith("cal.com")} check — swap the
         * validator's dot-boundary test for either and this case goes red on
         * {@code https://evilcal.com/...}.
         */
        @Test
        void aNonCalComHostIsRefusedByTheServer() throws Exception {
            TestAuthentication.authenticate(coach);
            for (String hostile : new String[]{
                    "https://evilcal.com/coach-a",              // no dot boundary
                    "https://cal.com.phish.example/coach-a",    // suffix, not host
                    "https://cal.com@evil.example/coach-a",     // userinfo trick
                    "http://cal.com/coach-a",                   // plaintext
                    "https://calendly.com/coach-a",             // a different provider
            }) {
                mockMvc.perform(patchProfile(hostile))
                        .andExpect(status().isBadRequest());
            }
            // Refused means NOT WRITTEN, not merely not echoed.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM coach_profiles WHERE coach_id = ?",
                    Integer.class, coach.getId())).isZero();
        }

        /**
         * The length cap. {@code booking_url} is {@code text} with no DB
         * ceiling on purpose, so {@code @Size} is the only thing stopping a
         * coach from parking an arbitrarily large blob on their row — a real
         * cal.com host with a megabyte of query string clears the allowlist.
         */
        @Test
        void anOverlongLinkIsRefusedEvenOnTheRightHost() throws Exception {
            TestAuthentication.authenticate(coach);
            String tooLong = "https://cal.com/coach-a?q=" + "a".repeat(500);
            assertThat(tooLong).hasSizeGreaterThan(512);

            mockMvc.perform(patchProfile(tooLong)).andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM coach_profiles WHERE coach_id = ?",
                    Integer.class, coach.getId())).isZero();

            // One character under the cap on the same host is accepted, so the
            // case proves the LENGTH rule and not the host rule.
            String justFits = "https://cal.com/coach-a?q=" + "a".repeat(512 - 26);
            assertThat(justFits).hasSize(512);
            mockMvc.perform(patchProfile(justFits)).andExpect(status().isOk());
        }

        /**
         * Ownership. The endpoint carries no id, so a coach can only ever write
         * the row keyed by their own principal — point the service at anything
         * other than {@code currentUser.require().userId()} and this fails.
         */
        @Test
        void aCoachWritesOnlyTheirOwnRow() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(patchProfile("https://cal.com/coach-a")).andExpect(status().isOk());

            User otherCoach = saveUser("coach.other@test.invalid", UserRole.COACH, orgA);
            TestAuthentication.authenticate(otherCoach);
            // The other coach sees their OWN (empty) profile, never coach A's…
            mockMvc.perform(get("/api/v1/coach/profile"))
                    .andExpect(jsonPath("$.bookingUrl", nullValue()));
            mockMvc.perform(patchProfile("https://cal.com/coach-other"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingUrl", is("https://cal.com/coach-other")));

            // …and coach A's row is untouched.
            assertThat(jdbc.queryForObject(
                    "SELECT booking_url FROM coach_profiles WHERE coach_id = ?",
                    String.class, coach.getId())).isEqualTo("https://cal.com/coach-a");
            assertThat(jdbc.queryForObject(
                    "SELECT booking_url FROM coach_profiles WHERE coach_id = ?",
                    String.class, otherCoach.getId())).isEqualTo("https://cal.com/coach-other");
        }

        @Test
        void nonCoachRolesCannotPublishABookingLink() throws Exception {
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(patchProfile("https://cal.com/impostor"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/coach/profile")).andExpect(status().isForbidden());

            TestAuthentication.authenticate(saveUser("orgadmin.profile@test.invalid",
                    UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(patchProfile("https://cal.com/impostor"))
                    .andExpect(status().isForbidden());
        }
    }

    /* ------------------------------------- the founder's side of the relationship */

    /**
     * {@code GET /api/v1/me/coaches} — the first surface in the app that tells a
     * founder who their coach is. It is the coach console's visibility rule read
     * backwards, so every asymmetry here is a leak: a founder must see exactly
     * the coaches who can see them.
     */
    @Nested
    class MyCoaches {

        @Test
        void founderSeesTheCoachWhoCanSeeThem_withTheBookingLink() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(patchProfile("https://cal.com/coach-a")).andExpect(status().isOk());

            // Visible through the COHORT grant…
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("coach.a")))
                    .andExpect(jsonPath("$[0].bookingUrl", is("https://cal.com/coach-a")))
                    // Mirror of the roster's rule: no contact data crosses either way.
                    .andExpect(jsonPath("$[0].email").doesNotExist());

            // …and through the DIRECT grant.
            TestAuthentication.authenticate(founderDirect);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("coach.a")));
        }

        @Test
        void aCoachWithNoLinkIsListedHonestly_notHidden() throws Exception {
            // coach.a published nothing. The founder is still told who their
            // coach is; the card decides what to say about the missing link.
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("coach.a")))
                    .andExpect(jsonPath("$[0].bookingUrl", nullValue()));
        }

        @Test
        void severalCoachesAreAllListed_orderedByName() throws Exception {
            // The cohort+direct union genuinely allows this: a second coach with
            // a direct grant on the same founder.
            User second = saveUser("coach.zz@test.invalid", UserRole.COACH, orgA);
            insertGrant(orgA.getId(), second.getId(), null, founderInCohort.getId());

            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name", is("coach.a")))
                    .andExpect(jsonPath("$[1].name", is("coach.zz")));
        }

        @Test
        void aFounderOutsideEveryUnionSeesNoCoach() throws Exception {
            TestAuthentication.authenticate(founderUnassigned);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            // Being in an UNGRANTED cohort is not a relationship either.
            TestAuthentication.authenticate(founderOtherCohort);
            mockMvc.perform(get(MY_COACHES)).andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void anotherTenantsCoachNeverAppears() throws Exception {
            TestAuthentication.authenticate(founderB);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("coach.b")));
        }

        /**
         * The two predicates the reverse direction has to add itself — the
         * shared fragment says nothing about the COACH's own row, because
         * forwards the coach is the authenticated caller.
         */
        @Test
        void aSuspendedOrDemotedCoachStopsBeingOfferedAsBookable() throws Exception {
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", coach.getId());
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES)).andExpect(jsonPath("$", hasSize(0)));

            jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'MEMBER' WHERE id = ?",
                    coach.getId());
            mockMvc.perform(get(MY_COACHES)).andExpect(jsonPath("$", hasSize(0)));
        }

        /**
         * An org-less account reaches the SQL with a null {@code :orgId}:
         * Postgres infers the parameter type from the comparison, the tenant
         * equality is never true, and the answer is an empty list rather than
         * a 500. Pinned because it is a real request shape, not because the
         * controller guards it — it does not.
         */
        @Test
        void anOrgLessAccountGetsAnEmptyListNotAnError() throws Exception {
            TestAuthentication.authenticate(saveUser("orgless@test.invalid", UserRole.MEMBER, null));
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        /**
         * The one case the shared relation cannot catch, and the reason
         * {@code cu.organization_id = :orgId} exists in its own right.
         *
         * <p>This grant is internally consistent as far as the relation is
         * concerned — {@code org_id} and {@code member_id} are org A's, so the
         * founder, the grant and {@code :orgId} all agree — but the COACH is
         * org B's. Nothing in the app writes such a row; a bad migration, a
         * support script or a future admin surface could. Drop the coach's own
         * org equality and org B's coach, plus their booking link, lands on an
         * org-A founder's dashboard.
         */
        @Test
        void aCrossOrgGrantNeverSurfacesAForeignCoach() throws Exception {
            TestAuthentication.authenticate(coachB);
            mockMvc.perform(patchProfile("https://cal.com/coach-b")).andExpect(status().isOk());

            // org A's grant row, org A's founder — but org B's coach.
            insertGrant(orgA.getId(), coachB.getId(), null, founderInCohort.getId());

            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    // Only the legitimate org-A coach; coach.b is not reachable
                    // from this founder however the grant table is written.
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("coach.a")));
        }

        @Test
        void aCallerWhoIsNotAVisibleMemberSeesNothing() throws Exception {
            // Not a 403 — the relation is the authority, and an admin simply has
            // no coach. The suspended founder drops out of the union both ways.
            TestAuthentication.authenticate(saveUser("orgadmin.coaches@test.invalid",
                    UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get(MY_COACHES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                    founderInCohort.getId());
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(MY_COACHES)).andExpect(jsonPath("$", hasSize(0)));
        }
    }

    /* ---------------------------------------------------- grant management */

    @Nested
    class GrantManagement {

        @Test
        void orgAdminCreatesListsAndDeletesGrants() throws Exception {
            User newCoach = saveUser("coach.new@test.invalid", UserRole.COACH, orgA);
            TestAuthentication.authenticate(saveUser("orgadmin.crud@test.invalid",
                    UserRole.ORG_ADMIN, orgA));

            mockMvc.perform(post(grants(orgA))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\"}"
                                    .formatted(newCoach.getId(), cohort2)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.coachEmail", is("coach.new@test.invalid")))
                    .andExpect(jsonPath("$.cohortName", is("Cohort Two")));

            mockMvc.perform(post(grants(orgA))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"memberId\":\"%s\"}"
                                    .formatted(newCoach.getId(), founderUnassigned.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.memberEmail", is("founder.unassigned@test.invalid")));

            // The 2 seeded org-A grants + the 2 just created (org B's stay invisible).
            mockMvc.perform(get(grants(orgA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(4)));

            UUID grantId = jdbc.queryForObject(
                    "SELECT id FROM coach_assignments WHERE cohort_id = ?", UUID.class, cohort2);
            mockMvc.perform(delete(grants(orgA) + "/" + grantId))
                    .andExpect(status().isNoContent());
        }

        @Test
        void invalidGrainAndTargetsAreRejected() throws Exception {
            TestAuthentication.authenticate(saveUser("orgadmin.val@test.invalid",
                    UserRole.ORG_ADMIN, orgA));

            // both / neither grain
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\",\"memberId\":\"%s\"}"
                                    .formatted(coach.getId(), cohort2, founderUnassigned.getId())))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\"}".formatted(coach.getId())))
                    .andExpect(status().isBadRequest());

            // a MEMBER is not a coach
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\"}"
                                    .formatted(founderInCohort.getId(), cohort2)))
                    .andExpect(status().isBadRequest());

            // duplicates
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\"}"
                                    .formatted(coach.getId(), cohort1)))
                    .andExpect(status().isBadRequest());

            // foreign rows read as absent: org B's cohort, member and coach
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\"}"
                                    .formatted(coach.getId(), cohortB)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"memberId\":\"%s\"}"
                                    .formatted(coach.getId(), founderB.getId())))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post(grants(orgA)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coachId\":\"%s\",\"cohortId\":\"%s\"}"
                                    .formatted(coachB.getId(), cohort2)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void foreignAdminAndNonAdminsAreRejected() throws Exception {
            // An org-B admin cannot touch org A's grants (403 at @orgAccess)…
            TestAuthentication.authenticate(saveUser("orgadmin.b@test.invalid",
                    UserRole.ORG_ADMIN, orgB));
            mockMvc.perform(get(grants(orgA))).andExpect(status().isForbidden());
            // …and cannot delete them through their own org's path (404 at data layer).
            UUID orgAGrant = jdbc.queryForObject(
                    "SELECT id FROM coach_assignments WHERE org_id = ? LIMIT 1",
                    UUID.class, orgA.getId());
            mockMvc.perform(delete(grants(orgB) + "/" + orgAGrant))
                    .andExpect(status().isNotFound());

            // A coach cannot manage grants at all.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(grants(orgA))).andExpect(status().isForbidden());

            // Nor can a member.
            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(grants(orgA))).andExpect(status().isForbidden());
        }
    }

    /* ----------------------------------------------------- exercise review */

    @Nested
    class ExerciseReview {

        @Test
        void coachReviewsAVisibleFoundersSubmission() throws Exception {
            // An org admin's review payload carries the member's email…
            TestAuthentication.authenticate(saveUser("orgadmin.review@test.invalid",
                    UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get(review(orgA, exerciseAssignmentVisible)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberEmail", is("founder.cohort@test.invalid")));

            // …a coach's does not (coach_sees excludes contact data), name only.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(review(orgA, exerciseAssignmentVisible)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberName", is("founder.cohort")))
                    .andExpect(jsonPath("$.memberEmail", nullValue()));
            mockMvc.perform(post("/api/organizations/" + orgA.getId()
                            + "/exercise-assignments/" + exerciseAssignmentVisible + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"Sharpen the pricing assumptions in row two.\"}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/organizations/" + orgA.getId()
                            + "/exercise-assignments/" + exerciseAssignmentVisible + "/mark-reviewed"))
                    .andExpect(status().isOk());
        }

        @Test
        void unassignedFoundersSubmissionIs404ForTheCoach() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(review(orgA, exerciseAssignmentHidden)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void foreignCoachAndMemberAreRejectedAtTheGate() throws Exception {
            TestAuthentication.authenticate(coachB);
            mockMvc.perform(get(review(orgA, exerciseAssignmentVisible)))
                    .andExpect(status().isForbidden());

            TestAuthentication.authenticate(founderInCohort);
            mockMvc.perform(get(review(orgA, exerciseAssignmentVisible)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void coachCannotDistributeExercises() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/exercise-assignments"))
                    .andExpect(status().isForbidden());
        }
    }

    /* ------------------------------------------------------------ seeding */

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        // Flush so the row is visible to the JdbcTemplate seeding below.
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

    private void insertGrant(UUID orgId, UUID coachId, UUID cohortId, UUID memberId) {
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, ?)
                """, orgId, coachId, cohortId, memberId);
    }

    /**
     * Module One in cohort1: two LIVE tasks (one submitted by founderInCohort)
     * + one DRAFT. Module Two in the UNGRANTED cohort2: one LIVE task, also
     * submitted by founderInCohort — countable only through a direct grant.
     */
    private void seedProgram() {
        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 'ALL', 'UNLOCKED')
                """, moduleId, cohort1);
        UUID task1 = UUID.randomUUID();
        UUID task2 = UUID.randomUUID();
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, 'Task One', 'LIVE')", task1, moduleId);
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, 'Task Two', 'LIVE')", task2, moduleId);
        jdbc.update("INSERT INTO program_tasks (module_id, name, status) VALUES (?, 'Draft Task', 'DRAFT')", moduleId);
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, task1, founderInCohort.getId());
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status)
                VALUES (?, ?, 'DRAFT')
                """, task2, founderInCohort.getId());

        UUID module2Id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Module Two', 'ALL', 'UNLOCKED')
                """, module2Id, cohort2);
        UUID task3 = UUID.randomUUID();
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, 'Task Three', 'LIVE')", task3, module2Id);
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, task3, founderInCohort.getId());
    }

    /** One evaluated assessment for founderInCohort: pillar "Vision" at 72.50 / Strong. */
    private void seedPillarScores() {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Coach Test Pipeline', 'PUBLISHED', ?)
                """, pipelineId, coach.getId());
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name) VALUES (?, ?, 'Vision')",
                pillarId, pipelineId);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgA.getId(), founderInCohort.getId(), coach.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, 'EVALUATED', now())
                """, submissionId, assignmentId, founderInCohort.getId());
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, 72.50, 'Strong')
                """, submissionId, pillarId);
    }

    /** One exercise each for the visible and the unassigned founder, both SUBMITTED. */
    private void seedExercises() {
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Coach Test Exercise', 'PUBLISHED', ?)
                """, templateId, coach.getId());
        exerciseAssignmentVisible = insertExercise(templateId, founderInCohort.getId());
        exerciseAssignmentHidden = insertExercise(templateId, founderUnassigned.getId());
    }

    private UUID insertExercise(UUID templateId, UUID userId) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, templateId, orgA.getId(), userId, coach.getId());
        jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, assignmentId, userId);
        return assignmentId;
    }

    /** {@code PATCH /api/v1/coach/profile} with one booking-link value. */
    private static org.springframework.test.web.servlet.RequestBuilder patchProfile(String url) {
        return patch("/api/v1/coach/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingUrl\":\"%s\"}".formatted(url));
    }

    private String grants(Organization org) {
        return "/api/organizations/" + org.getId() + "/coach-assignments";
    }

    private String review(Organization org, UUID assignmentId) {
        return "/api/organizations/" + org.getId() + "/exercise-assignments/" + assignmentId
                + "/submission";
    }
}
