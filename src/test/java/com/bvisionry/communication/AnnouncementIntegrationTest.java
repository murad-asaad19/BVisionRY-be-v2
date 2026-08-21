package com.bvisionry.communication;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.event.CommunicationEvents;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cohort announcements end to end against the real schema (roadmap §7 item 20).
 *
 * <ul>
 *   <li>an org admin broadcasts to any cohort in their org, including a
 *       sub-org's (hierarchy), and never to another tenant's;</li>
 *   <li>a coach broadcasts ONLY to a cohort they hold a grant on — an
 *       unassigned cohort in their OWN org is refused at the data layer,
 *       after both the role and the org gates have passed;</li>
 *   <li>members and instructors cannot post at all;</li>
 *   <li>a body carrying markup — raw or entity-encoded — is REFUSED at the
 *       endpoint, and ordinary text is stored byte-for-byte (the rule itself is
 *       pinned Docker-free in {@code AnnouncementSanitizerTest});</li>
 *   <li>recipients are the target cohort's enrolled members at send time —
 *       nobody else — and every post writes an audit row;</li>
 *   <li>a recipient can flag a post; someone the post never reached cannot, a
 *       suspended member has no standing, and a cross-org enrolment row buys
 *       neither delivery nor standing;</li>
 *   <li>the resulting flag is on the wire for an org admin and NOT for a coach,
 *       who may be its subject.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@EnabledIfDockerAvailable
class AnnouncementIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    private Organization orgA;
    private Organization subOrgA;   // child of orgA — the hierarchy case
    private Organization orgB;      // another tenant entirely

    private User orgAdminA;
    private User coach;             // COACH in orgA, granted cohortGranted only
    private User memberIn;          // orgA, enrolled in cohortGranted
    private User memberAlsoIn;      // orgA, enrolled in cohortGranted
    private User memberOther;       // orgA, enrolled in cohortOther ONLY
    private User instructor;        // orgA
    private User orgAdminB;         // ORG_ADMIN of the other tenant

    private UUID cohortGranted;     // orgA, the coach's grant
    private UUID cohortOther;       // orgA, NO grant
    private UUID cohortSub;         // subOrgA
    private UUID cohortB;           // orgB

    @BeforeEach
    void seed() {
        orgA = saveOrg("Announce Org A", null);
        subOrgA = saveOrg("Announce Sub A", orgA);
        orgB = saveOrg("Announce Org B", null);

        orgAdminA = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        orgAdminB = saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);
        coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);
        memberIn = saveUser("member.in@test.invalid", UserRole.MEMBER, orgA);
        memberAlsoIn = saveUser("member.also@test.invalid", UserRole.MEMBER, orgA);
        memberOther = saveUser("member.other@test.invalid", UserRole.MEMBER, orgA);
        instructor = saveUser("instructor.a@test.invalid", UserRole.INSTRUCTOR, orgA);

        cohortGranted = insertCohort(orgA.getId(), "Granted Cohort", memberIn.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortGranted, memberAlsoIn.getId());
        cohortOther = insertCohort(orgA.getId(), "Other Cohort", memberOther.getId());
        cohortSub = insertCohort(subOrgA.getId(), "Sub Cohort", null);
        cohortB = insertCohort(orgB.getId(), "Foreign Cohort", null);

        // The coach holds ONE whole-cohort grant, plus a DIRECT grant on a
        // founder of the ungranted cohort — a direct grant must never become a
        // licence to address that founder's cohort.
        insertGrant(orgA.getId(), coach.getId(), cohortGranted, null);
        insertGrant(orgA.getId(), coach.getId(), null, memberOther.getId());
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* --------------------------------------------------------- authorship */

    @Nested
    class WhoMayBroadcast {

        @Test
        void orgAdminPostsToTheirOwnCohort() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Demo day moved to Friday."))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.cohortName", is("Granted Cohort")))
                    .andExpect(jsonPath("$.authorName", is("admin.a")))
                    .andExpect(jsonPath("$.body", is("Demo day moved to Friday.")))
                    .andExpect(jsonPath("$.flagged", is(false)));
        }

        @Test
        void parentOrgAdminPostsToASubOrgCohort() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(subOrgA.getId(), cohortSub, "Welcome to the sub-org."))
                    .andExpect(status().isCreated());
        }

        @Test
        void coachPostsToTheirAssignedCohort() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Office hours at 3."))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.authorName", is("coach.a")));
        }

        @Test
        void coachIsDeniedACohortTheyAreNotAssignedTo() throws Exception {
            // Role gate: passed (COACH). Org gate: passed (their own org).
            // The refusal can only come from the data layer, and it is a 404 —
            // a coach may not learn which cohort ids exist outside their grants.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortOther, "Not mine to send."))
                    .andExpect(status().isNotFound());
            assertThat(countAnnouncements(cohortOther)).isZero();
        }

        @Test
        void adminOfAnotherTenantCannotPostToThisOrgsCohort() throws Exception {
            TestAuthentication.authenticate(orgAdminB);
            // Their own path org: the cohort is not in it -> absent.
            mockMvc.perform(postAnnouncement(orgB.getId(), cohortGranted, "Cross-tenant."))
                    .andExpect(status().isNotFound());
            // The victim's path org: refused above the service entirely.
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Cross-tenant."))
                    .andExpect(status().isForbidden());
            assertThat(countAnnouncements(cohortGranted)).isZero();
        }

        @Test
        void membersAndInstructorsCannotPost() throws Exception {
            TestAuthentication.authenticate(memberIn);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Hi all."))
                    .andExpect(status().isForbidden());

            TestAuthentication.authenticate(instructor);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Hi all."))
                    .andExpect(status().isForbidden());

            assertThat(countAnnouncements(cohortGranted)).isZero();
        }

        @Test
        void broadcastTargetsFollowTheCallersOwnAuthority() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/announcement-cohorts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].name", contains("Granted Cohort", "Other Cohort")));

            // The coach's picker cannot even OFFER the cohort the post endpoint
            // would refuse — and the direct grant does not add one.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/announcement-cohorts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("Granted Cohort")));
        }

        @Test
        void broadcastTargetsCarryEachCohortsLaunchState() throws Exception {
            // Information, NOT a filter. The DRAFT cohort is still listed —
            // its announcement history stays staff's to read — but it now says
            // it is a draft, so the picker's composer can explain the refusal
            // before anything is typed instead of after the post is rejected.
            jdbc.update("UPDATE cohorts SET status = 'DRAFT' WHERE id = ?", cohortOther);

            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/announcement-cohorts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].name", contains("Granted Cohort", "Other Cohort")))
                    .andExpect(jsonPath("$[*].status", contains("LAUNCHED", "DRAFT")));
        }
    }

    /* ------------------------------------------------------- sanitisation */

    /**
     * The endpoint's half of the plain-text boundary — that the rule is WIRED
     * and that a refusal stores nothing. Which strings are plain text is
     * enumerated Docker-free in {@code AnnouncementSanitizerTest}; duplicating
     * that table here would only make it skippable.
     */
    @Nested
    class BodySanitisation {

        @Test
        void ordinaryTextIsStoredExactlyAsTyped() throws Exception {
            // Ampersands and comparison signs are punctuation, not markup, and
            // the column holds the author's characters — no escaping, no
            // rewrite, so no reader has to guess which it is looking at.
            String body = "Demo day & drinks < 5pm: https://bvisionry.test/x";
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body", is(body)));

            entityManager.flush();
            assertThat(jdbc.queryForObject(
                    "SELECT body FROM announcements WHERE cohort_id = ?", String.class, cohortGranted))
                    .isEqualTo(body);
        }

        @Test
        void aBodyCarryingMarkupIsRefusedRatherThanRewritten() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted,
                            "Read <b>this</b> <script>alert('xss')</script>now"))
                    .andExpect(status().isBadRequest());
            assertThat(countAnnouncements(cohortGranted)).isZero();
        }

        @Test
        void anEntityEncodedBodyIsRefusedToo() throws Exception {
            // The one that matters: the sanitiser DECODES entities, so a
            // strip-and-store pipeline would have written live `<script>` into
            // a column every reader treats as plain text.
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted,
                            "&lt;script&gt;alert(1)&lt;/script&gt;"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted,
                            "&amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt;"))
                    .andExpect(status().isBadRequest());
            assertThat(countAnnouncements(cohortGranted)).isZero();
        }

        @Test
        void anEmptyBodyIsRejectedByValidation() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "   "))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aBodyPastTheDeliverableCeilingIsRejected() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "x".repeat(501)))
                    .andExpect(status().isBadRequest());
            assertThat(countAnnouncements(cohortGranted)).isZero();
        }
    }

    /* ------------------------------------------------- delivery + audit */

    @Nested
    class DeliveryAndAudit {

        @Test
        void recipientsAreTheCohortsMembersAtSendTimeAndNobodyElse() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Standup at 9."))
                    .andExpect(status().isCreated());

            CommunicationEvents.AnnouncementPosted posted = singlePostedEvent();
            assertThat(posted.recipientIds())
                    .containsExactlyInAnyOrder(memberIn.getId(), memberAlsoIn.getId())
                    // A member of another cohort in the SAME org receives nothing.
                    .doesNotContain(memberOther.getId())
                    // Nor does the author, nor an unenrolled coach.
                    .doesNotContain(orgAdminA.getId(), coach.getId());
            assertThat(posted.cohortName()).isEqualTo("Granted Cohort");
            assertThat(posted.body()).isEqualTo("Standup at 9.");
        }

        /**
         * A DRAFT cohort — including one temporarily unlaunched — is invisible
         * to members ({@code CohortVisibility}), so a broadcast to it would
         * silently reach nobody: refused with a 400 pointing at launch, and no
         * row is written. Relaunched, the same author's post lands and reaches
         * the enrolled members.
         */
        @Test
        void postingToAnUnlaunchedCohortIsRefused_untilRelaunch() throws Exception {
            jdbc.update("UPDATE cohorts SET status = 'DRAFT' WHERE id = ?", cohortGranted);

            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Sneak peek."))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail",
                            is("This cohort isn't live — launch it before announcing.")));
            assertThat(countAnnouncements(cohortGranted)).isZero();

            jdbc.update("UPDATE cohorts SET status = 'LAUNCHED' WHERE id = ?", cohortGranted);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "We are live."))
                    .andExpect(status().isCreated());
            assertThat(singlePostedEvent().recipientIds())
                    .containsExactlyInAnyOrder(memberIn.getId(), memberAlsoIn.getId());
        }

        @Test
        void aSuspendedMemberIsNotARecipient() throws Exception {
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", memberAlsoIn.getId());
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Standup at 9."))
                    .andExpect(status().isCreated());

            assertThat(singlePostedEvent().recipientIds()).containsExactly(memberIn.getId());
        }

        @Test
        void everyPostWritesAnAuditRow() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "Office hours at 3."))
                    .andExpect(status().isCreated());
            entityManager.flush();

            List<UUID> actors = jdbc.queryForList("""
                    SELECT actor_id FROM audit_logs
                    WHERE action_type = 'ANNOUNCEMENT_POSTED' AND organization_id = ?
                    """, UUID.class, orgA.getId());
            assertThat(actors).containsExactly(coach.getId());
        }
    }

    /* -------------------------------------------------------------- feed */

    @Nested
    class Feed {

        @Test
        void theCohortFeedIsScopedTheSameWayPostingIs() throws Exception {
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(postAnnouncement(orgA.getId(), cohortGranted, "First."))
                    .andExpect(status().isCreated());
            entityManager.flush();

            mockMvc.perform(get(feedPath(orgA.getId(), cohortGranted)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].body", is("First.")))
                    .andExpect(jsonPath("$[0].authorName", is("admin.a")));

            // The coach may read the cohort they hold…
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(feedPath(orgA.getId(), cohortGranted)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
            // …and not the one they don't.
            mockMvc.perform(get(feedPath(orgA.getId(), cohortOther)))
                    .andExpect(status().isNotFound());
        }
    }

    /* ------------------------------------------------------------ report */

    @Nested
    class Reporting {

        @Test
        void aRecipientFlagsThePostAndItIsAudited() throws Exception {
            UUID announcementId = postAs(orgAdminA, cohortGranted, "Please ignore.");

            TestAuthentication.authenticate(memberIn);
            mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                    .andExpect(status().isNoContent());
            entityManager.flush();

            assertThat(jdbc.queryForObject(
                    "SELECT flagged_by FROM announcements WHERE id = ?", UUID.class, announcementId))
                    .isEqualTo(memberIn.getId());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_logs
                    WHERE action_type = 'ANNOUNCEMENT_REPORTED' AND entity_id = ?
                    """, Integer.class, announcementId)).isEqualTo(1);

            // The author's feed shows the flag — the whole moderation surface.
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(get(feedPath(orgA.getId(), cohortGranted)))
                    .andExpect(jsonPath("$[0].flagged", is(true)));
        }

        @Test
        void theFlagIsAnOrgAdminSignalAndIsNotOnTheWireForACoach() throws Exception {
            // A coach reads the SAME feed, and a report may be ABOUT them. Told
            // that one was filed, with no context and no recourse but continuing
            // authority over a small cohort, they can very nearly name the
            // reporter. So the suppression is server-side: the coach's response
            // carries flagged=false, not a flagged=true the client agrees to
            // hide. Hiding it in React only would still ship it over the wire.
            UUID announcementId = postAs(orgAdminA, cohortGranted, "Please ignore.");
            TestAuthentication.authenticate(memberIn);
            mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                    .andExpect(status().isNoContent());
            entityManager.flush();

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(feedPath(orgA.getId(), cohortGranted)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id", is(announcementId.toString())))
                    .andExpect(jsonPath("$[0].flagged", is(false)));

            // …while the org admin, who can actually act on it, does see it.
            TestAuthentication.authenticate(orgAdminA);
            mockMvc.perform(get(feedPath(orgA.getId(), cohortGranted)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].flagged", is(true)));
        }

        @Test
        void aSuspendedMemberHasNoStandingToReport() throws Exception {
            // Standing to report is standing to have received; recipientIds
            // filters on ACTIVE, so isCohortMember must too, or a suspended
            // member holding a live token slips between the two.
            UUID announcementId = postAs(orgAdminA, cohortGranted, "Please ignore.");
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", memberIn.getId());

            TestAuthentication.authenticate(memberIn);
            mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                    .andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject(
                    "SELECT flagged_at FROM announcements WHERE id = ?", Object.class, announcementId))
                    .isNull();
        }

        @Test
        void repeatReportsAreATrueNoOp() throws Exception {
            // No rate-limit filter covers this route, so "audit every call"
            // would hand one recipient an unbounded audit-row pen. The first
            // report is the whole event; the rest change nothing.
            UUID announcementId = postAs(orgAdminA, cohortGranted, "Please ignore.");

            TestAuthentication.authenticate(memberIn);
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                        .andExpect(status().isNoContent());
            }
            entityManager.flush();

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_logs
                    WHERE action_type = 'ANNOUNCEMENT_REPORTED' AND entity_id = ?
                    """, Integer.class, announcementId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT flagged_by FROM announcements WHERE id = ?", UUID.class, announcementId))
                    .isEqualTo(memberIn.getId());
        }

        @Test
        void someoneThePostNeverReachedCannotReportIt() throws Exception {
            UUID announcementId = postAs(orgAdminA, cohortGranted, "Please ignore.");

            TestAuthentication.authenticate(memberOther);
            mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                    .andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject(
                    "SELECT flagged_at FROM announcements WHERE id = ?", Object.class, announcementId))
                    .isNull();
        }

        @Test
        void aCrossOrgEnrolmentRowBuysNeitherDeliveryNorStanding() throws Exception {
            // `cohort_members` carries no org-consistency constraint, so a row
            // pairing orgB's user with orgA's cohort is structurally insertable
            // — by a bug, a bad import, or a future feature. The only barrier is
            // the `u.organization_id = :orgId` both announcement queries carry,
            // and this is the test that fails if either one loses it.
            User memberB = saveUser("member.b@test.invalid", UserRole.MEMBER, orgB);
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohortGranted, memberB.getId());

            UUID announcementId = postAs(orgAdminA, cohortGranted, "Standup at 9.");
            assertThat(singlePostedEvent().recipientIds())
                    .doesNotContain(memberB.getId())
                    .containsExactlyInAnyOrder(memberIn.getId(), memberAlsoIn.getId());

            TestAuthentication.authenticate(memberB);
            mockMvc.perform(post("/api/v1/announcements/" + announcementId + "/report"))
                    .andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject(
                    "SELECT flagged_at FROM announcements WHERE id = ?", Object.class, announcementId))
                    .isNull();
        }
    }

    /* ---------------------------------------------------------- fixtures */

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            postAnnouncement(UUID orgId, UUID cohortId, String body) {
        return post(feedPath(orgId, cohortId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + quote(body) + "}");
    }

    private static String feedPath(UUID orgId, UUID cohortId) {
        return "/api/organizations/" + orgId + "/cohorts/" + cohortId + "/announcements";
    }

    /** Minimal JSON string literal — the bodies here carry quotes and angle brackets. */
    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private UUID postAs(User author, UUID cohortId, String body) throws Exception {
        TestAuthentication.authenticate(author);
        mockMvc.perform(postAnnouncement(author.getOrganization().getId(), cohortId, body))
                .andExpect(status().isCreated());
        entityManager.flush();
        return jdbc.queryForObject("SELECT id FROM announcements WHERE cohort_id = ?",
                UUID.class, cohortId);
    }

    private CommunicationEvents.AnnouncementPosted singlePostedEvent() {
        List<CommunicationEvents.AnnouncementPosted> posted = events
                .stream(CommunicationEvents.AnnouncementPosted.class).toList();
        assertThat(posted).hasSize(1);
        return posted.get(0);
    }

    private Integer countAnnouncements(UUID cohortId) {
        // Hibernate defers the INSERT to flush; JdbcTemplate does not see the
        // persistence context, so every raw read of `announcements` flushes first.
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM announcements WHERE cohort_id = ?",
                Integer.class, cohortId);
    }

    private Organization saveOrg(String name, Organization parent) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        org.setParentOrganization(parent);
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
        if (memberId != null) {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", id, memberId);
        }
        return id;
    }

    private void insertGrant(UUID orgId, UUID coachId, UUID cohortId, UUID memberId) {
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, ?)
                """, orgId, coachId, cohortId, memberId);
    }
}
