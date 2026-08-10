package com.bvisionry.exercise;

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
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exercise quality tag (redesign spec §4 + §11, V170): a reviewer's
 * metadata label on a submission, set on the review or re-set afterwards,
 * validated against the live §7 tag set with its label SNAPSHOTTED at tagging
 * time.
 *
 * <p>The three things that would actually hurt if they broke:
 * <ul>
 *   <li>the tag never reaches the member (it is staff metadata, not a grade);</li>
 *   <li>the tag never moves the participation score (§4: "metadata only");</li>
 *   <li>renaming or deleting a tag never rewrites what a reviewer already said.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ExerciseQualityTagIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager em;

    private Organization org;
    private User admin;
    private User coach;
    private User founder;
    private UUID assignmentId;
    private UUID submissionId;

    @BeforeEach
    void seed() {
        org = saveOrg("Quality Tag Org");
        admin = saveUser("qt.admin@test.invalid", UserRole.ORG_ADMIN);
        coach = saveUser("qt.coach@test.invalid", UserRole.COACH);
        founder = saveUser("qt.founder@test.invalid", UserRole.MEMBER);

        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, org_id, name, status) VALUES (?, ?, 'QT Cohort', 'LAUNCHED')",
                cohortId, org.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, founder.getId());
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, org.getId(), coach.getId(), cohortId);

        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Pricing Grid', 'PUBLISHED', ?)
                """, templateId, admin.getId());
        assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, templateId, org.getId(), founder.getId(), admin.getId());
        submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_submissions (id, assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, 'SUBMITTED', now())
                """, submissionId, assignmentId, founder.getId());
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------------------- tagging */

    @Nested
    class Tagging {

        @Test
        void markReviewedCarriesTheTagAndSnapshotsItsLabel() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"strong\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("REVIEWED")))
                    .andExpect(jsonPath("$.qualityTagKey", is("strong")))
                    .andExpect(jsonPath("$.qualityTagLabel", is("Strong")))
                    // §7b: stamped and attributed.
                    .andExpect(jsonPath("$.qualityTaggedAt", notNullValue()))
                    .andExpect(jsonPath("$.qualityTaggedByName", is("qt.admin")));
        }

        @Test
        void markReviewedWithoutABodyStillWorksAndLeavesTheTagNull() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(post(url("/mark-reviewed")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("REVIEWED")))
                    .andExpect(jsonPath("$.qualityTagKey", nullValue()));
        }

        @Test
        void reviewPayloadOffersTheLiveTagSetSoNoPlatformConfigReadIsNeeded() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(url("/submission")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qualityTagOptions", hasSize(3)))
                    .andExpect(jsonPath("$.qualityTagOptions[0].key", is("thin")))
                    .andExpect(jsonPath("$.qualityTagOptions[2].label", is("Strong")));
        }

        @Test
        void theCoachDoorTagsToo_andRetaggingRestampsSection7b() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"thin\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qualityTagLabel", is("Thin")))
                    .andExpect(jsonPath("$.qualityTaggedByName", is("qt.coach")));

            // Re-tag on the already-reviewed copy: new key, new stamp, new hand.
            TestAuthentication.authenticate(admin);
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"adequate\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qualityTagKey", is("adequate")))
                    .andExpect(jsonPath("$.qualityTagLabel", is("Adequate")))
                    .andExpect(jsonPath("$.qualityTaggedByName", is("qt.admin")))
                    // Metadata only: the review state is untouched by a re-tag.
                    .andExpect(jsonPath("$.status", is("REVIEWED")));
        }

        @Test
        void clearingSendsNullAndWipesAllFourColumns() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"thin\"}")).andExpect(status().isOk());
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qualityTagKey", nullValue()))
                    .andExpect(jsonPath("$.qualityTagLabel", nullValue()))
                    .andExpect(jsonPath("$.qualityTaggedAt", nullValue()))
                    .andExpect(jsonPath("$.qualityTaggedByName", nullValue()));
            flush();
            assertThat(jdbc.queryForObject(
                    "SELECT quality_tagged_by FROM exercise_submissions WHERE id = ?",
                    UUID.class, submissionId)).isNull();
        }

        @Test
        void anUnknownKeyIs400_onBothDoors() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"brilliant\"}"))
                    .andExpect(status().isBadRequest());
            // …and the refusal was atomic: no half-review.
            flush();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM exercise_submissions WHERE id = ?", String.class, submissionId))
                    .isEqualTo("SUBMITTED");

            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"strong\"}")).andExpect(status().isOk());
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"brilliant\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anUnreviewedCopyCannotBeTaggedStandalone() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"strong\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aRenamedOrDeletedTagNeverRewritesWhatTheReviewerSaid() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"thin\"}")).andExpect(status().isOk());

            // A super admin edits the §7 tag set: "thin" is gone, "strong" renamed.
            jdbc.update("""
                    INSERT INTO platform_settings (key, value_text) VALUES ('scoring.quality_tags', ?)
                    ON CONFLICT (key) DO UPDATE SET value_text = EXCLUDED.value_text
                    """, "{\"tags\":[{\"key\":\"strong\",\"label\":\"Exemplary\"}]}");

            mockMvc.perform(get(url("/submission")))
                    .andExpect(status().isOk())
                    // The stored snapshot stands…
                    .andExpect(jsonPath("$.qualityTagKey", is("thin")))
                    .andExpect(jsonPath("$.qualityTagLabel", is("Thin")))
                    // …while the offered pills follow the live config.
                    .andExpect(jsonPath("$.qualityTagOptions", hasSize(1)))
                    .andExpect(jsonPath("$.qualityTagOptions[0].label", is("Exemplary")));

            // And a retired key can no longer be applied.
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"thin\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aMemberCannotReachEitherWrite() throws Exception {
            TestAuthentication.authenticate(founder);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"strong\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"strong\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    /* ------------------------------------------------- who may see the tag */

    @Nested
    class Visibility {

        @Test
        void theMemberSeesNeitherTheTagNorTheTagSetOnTheirOwnSheet() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"thin\"}")).andExpect(status().isOk());

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/my/exercises/" + submissionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("REVIEWED")))
                    .andExpect(jsonPath("$.qualityTagKey", nullValue()))
                    .andExpect(jsonPath("$.qualityTagLabel", nullValue()))
                    .andExpect(jsonPath("$.qualityTaggedAt", nullValue()))
                    .andExpect(jsonPath("$.qualityTaggedByName", nullValue()))
                    .andExpect(jsonPath("$.qualityTagOptions", emptyIterable()));
        }

        @Test
        void theFounderProfileWorkRowCarriesTheLabel_onBothStaffDoors() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"adequate\"}")).andExpect(status().isOk());
            flush();

            mockMvc.perform(get("/api/organizations/" + org.getId()
                            + "/members/" + founder.getId() + "/founder-profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.work[?(@.type == 'EXERCISE')].qualityTagLabel",
                            is(java.util.List.of("Adequate"))));

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.work[?(@.type == 'EXERCISE')].qualityTagLabel",
                            is(java.util.List.of("Adequate"))));
        }

        @Test
        void theCoachQueueChipShowsThePreviousTagWhenACopyComesBack() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(markReviewed("{\"qualityTagKey\":\"thin\"}")).andExpect(status().isOk());
            // Sent back and resubmitted: the copy re-enters the queue still
            // carrying the reviewer's earlier read of it.
            mockMvc.perform(post(url("/request-changes"))).andExpect(status().isOk());
            flush();
            jdbc.update("UPDATE exercise_submissions SET status = 'SUBMITTED' WHERE id = ?", submissionId);

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/queue"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].qualityTagLabel", is("Thin")))
                    .andExpect(jsonPath("$.items[0].resubmitted", is(true)));
        }
    }

    /* ------------------------------------------------------- the invariant */

    @Nested
    class NeverInTheFormula {

        /**
         * Spec §4: the tag is metadata. Tagging must not move a single number
         * on the Engagement Record — the assignments category counts
         * submitted-vs-assigned and nothing else.
         */
        @Test
        void taggingDoesNotMoveTheParticipationScore() throws Exception {
            TestAuthentication.authenticate(admin);
            String engagement = "/api/organizations/" + org.getId()
                    + "/members/" + founder.getId() + "/engagement";

            mockMvc.perform(post(url("/mark-reviewed"))).andExpect(status().isOk());
            String before = engagementBody(engagement);

            tag("thin");
            String afterThin = engagementBody(engagement);

            tag("strong");
            String afterStrong = engagementBody(engagement);

            assertThat(afterThin).isEqualTo(before);
            assertThat(afterStrong).isEqualTo(before);
        }

        private void tag(String key) throws Exception {
            mockMvc.perform(patch(url("/quality-tag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qualityTagKey\":\"" + key + "\"}"))
                    .andExpect(status().isOk());
            flush();
        }

        /** {@code computedAt} is a clock, not a score — everything else must match byte for byte. */
        private String engagementBody(String url) throws Exception {
            return mockMvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll("\"computedAt\":\"[^\"]*\"", "\"computedAt\":\"*\"");
        }
    }

    /* ------------------------------------------------------------ plumbing */

    /**
     * The whole test runs in one transaction, so a JPA write stays in the
     * persistence context until something flushes it — and every read surface
     * below (queue, profile, engagement) is raw SQL. Flush + clear is what
     * makes "the reviewer saved it" visible to them, as it would be in prod.
     */
    private void flush() {
        em.flush();
        em.clear();
    }

    private String url(String suffix) {
        return "/api/organizations/" + org.getId() + "/exercise-assignments/" + assignmentId + suffix;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder markReviewed(
            String body) {
        return post(url("/mark-reviewed")).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Organization saveOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setActive(true);
        return organizationRepository.saveAndFlush(o);
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
