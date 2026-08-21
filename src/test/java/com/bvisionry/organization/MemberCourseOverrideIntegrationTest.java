package com.bvisionry.organization;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/organizations/{orgId}/members/{memberId}/courses} end to end — the
 * admin override of an auto-enrolment (roadmap §7 item 10).
 *
 * <p>This is a DELETE on someone else's account, so the tenancy tests are the
 * point of the class. There are two independent gates and each is falsified
 * separately, because either one alone lets a cross-tenant removal through:
 * <ul>
 *   <li>the controller's class-level {@code @orgAccess.isInOrg(#orgId)} — can the
 *       caller administer this ORG at all;</li>
 *   <li>{@code MemberService#findMemberInOrg} — does the MEMBER named in the path
 *       actually belong to that org. This is the one a new endpoint forgets, and
 *       without it an org admin removes a stranger's course by pasting an id.</li>
 * </ul>
 * No method-level {@code @PreAuthorize} exists on either handler, deliberately:
 * Spring replaces the class-level annotation with a method-level one rather than
 * ANDing them, so adding one would delete the first gate above.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class MemberCourseOverrideIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization own;
    private Organization other;
    private UUID founder;
    private UUID outsider;
    private UUID course;

    @BeforeEach
    void setUp() {
        own = newOrg("Override Own");
        other = newOrg("Override Other");
        founder = newFounder(own);
        outsider = newFounder(other);
        course = newCourse(own.getId());
        enrol(founder, course, 45);
        enrol(outsider, course, 70);
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM enrolment_overrides WHERE course_id = ?", course);
        jdbc.update("DELETE FROM enrollment WHERE course_id = ?", course);
        jdbc.update("DELETE FROM auto_enrolments WHERE course_id = ?", course);
        jdbc.update("DELETE FROM course WHERE id = ?", course);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", founder, outsider);
        userRepository.findByEmail("test-org-admin@bvisionry.invalid").ifPresent(userRepository::delete);
        organizationRepository.deleteById(own.getId());
        organizationRepository.deleteById(other.getId());
        TestAuthentication.clear();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void anOrgAdminRemovesTheirOwnMemberFromACourseAndTheProgressSurvives() throws Exception {
        mockMvc.perform(delete(url(own.getId(), founder, course)).param("reason", "Wrong module"))
                .andExpect(status().isNoContent());

        assertThat(enrolmentStatus(founder)).isEqualTo("CANCELLED");
        // ONE column changed. The row is still there, and so is everything that
        // cascades off its id — content_progress, quiz_attempts, certificates.
        assertThat(progress(founder)).isEqualTo(45);
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM enrolment_overrides WHERE user_id = ? AND course_id = ?
                """, String.class, founder, course)).isEqualTo("Wrong module");
    }

    @Test
    void removingTwiceIsANoOpRatherThanASecondOverrideOrAnError() throws Exception {
        mockMvc.perform(delete(url(own.getId(), founder, course))).andExpect(status().isNoContent());
        mockMvc.perform(delete(url(own.getId(), founder, course))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM enrolment_overrides WHERE user_id = ? AND course_id = ?
                """, Integer.class, founder, course)).isEqualTo(1);
    }

    @Test
    void theCourseListNamesThePillarThatAutoEnrolledThemAndFlagsWhatWasRemoved() throws Exception {
        UUID pipeline = newPipeline();
        UUID pillar = newPillar(pipeline);
        UUID submission = newEvaluatedSubmission(pipeline, own.getId(), founder);
        jdbc.update("""
                INSERT INTO auto_enrolments (user_id, course_id, submission_id, pillar_id,
                                             band_position, outcome)
                VALUES (?, ?, ?, ?, 0, 'ENROLLED')
                """, founder, course, submission, pillar);

        mockMvc.perform(get(url(own.getId(), founder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseId", is(course.toString())))
                .andExpect(jsonPath("$[0].recommendedForPillar", is("Vision Clarity")))
                .andExpect(jsonPath("$[0].progressPct", is(45)))
                .andExpect(jsonPath("$[0].removed", is(false)));

        mockMvc.perform(delete(url(own.getId(), founder, course))).andExpect(status().isNoContent());

        // Still listed, now flagged. A row that vanished would be indistinguishable
        // from a removal that failed to save.
        mockMvc.perform(get(url(own.getId(), founder)))
                .andExpect(jsonPath("$[0].removed", is(true)))
                .andExpect(jsonPath("$[0].status", is("CANCELLED")));

        jdbc.update("DELETE FROM auto_enrolments WHERE submission_id = ?", submission);
        jdbc.update("DELETE FROM submissions WHERE id = ?", submission);
        jdbc.update("DELETE FROM assignments WHERE user_id = ?", founder);
        jdbc.update("DELETE FROM pillars WHERE pipeline_id = ?", pipeline);
        jdbc.update("DELETE FROM pipelines WHERE id = ?", pipeline);
    }

    // ------------------------------------------------------------------ tenancy

    /**
     * The gate a new endpoint forgets. The caller passes their OWN orgId — so the
     * class-level {@code isInOrg} check is satisfied and does nothing here — and a
     * founder id belonging to a DIFFERENT org. Only the membership guard stands
     * between that request and an admin reaching into another tenant's account.
     */
    @Test
    void anOrgAdminCannotRemoveAMemberOfAnotherOrgByPastingTheirId() throws Exception {
        mockMvc.perform(delete(url(own.getId(), outsider, course)))
                .andExpect(status().isBadRequest());

        assertThat(enrolmentStatus(outsider)).isEqualTo("ACTIVE");
        assertThat(overrideCount(outsider)).isZero();
    }

    @Test
    void anOrgAdminCannotReachIntoAnotherOrgById() throws Exception {
        mockMvc.perform(delete(url(other.getId(), outsider, course)))
                .andExpect(status().isForbidden());

        assertThat(enrolmentStatus(outsider)).isEqualTo("ACTIVE");
        assertThat(overrideCount(outsider)).isZero();
    }

    @Test
    void aPlainMemberCannotRemoveAnyoneEvenInTheirOwnOrg() throws Exception {
        // Authenticates as THIS class's own founder rather than
        // TestAuthentication.authenticateAsMember: that helper persists a user on a
        // FIXED email, and the Postgres container is a singleton shared with every
        // other IT in the JVM, so it collides on users_email_key with whoever ran
        // first. Reusing a fixture we already own needs no cleanup either.
        TestAuthentication.authenticate(userRepository.findById(founder).orElseThrow());

        mockMvc.perform(delete(url(own.getId(), founder, course)))
                .andExpect(status().isForbidden());

        assertThat(enrolmentStatus(founder)).isEqualTo("ACTIVE");
    }

    @Test
    void aCourseTheMemberIsNotOnIs404RatherThanASilentOverride() throws Exception {
        UUID unrelated = newCourse(own.getId());

        mockMvc.perform(delete(url(own.getId(), founder, unrelated)))
                .andExpect(status().isNotFound());

        assertThat(overrideCount(founder)).isZero();
        jdbc.update("DELETE FROM course WHERE id = ?", unrelated);
    }

    // ------------------------------------------------------------------ helpers

    private static String url(UUID orgId, UUID memberId) {
        return "/api/organizations/" + orgId + "/members/" + memberId + "/courses";
    }

    private static String url(UUID orgId, UUID memberId, UUID courseId) {
        return url(orgId, memberId) + "/" + courseId;
    }

    private Organization newOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setActive(true);
        return organizationRepository.save(o);
    }

    private UUID newFounder(Organization org) {
        User u = new User();
        u.setEmail("override-" + UUID.randomUUID() + "@test.invalid");
        u.setName("Founder");
        u.setRole(UserRole.MEMBER);
        u.setStatus(UserStatus.ACTIVE);
        u.setOrganization(org);
        return userRepository.save(u).getId();
    }

    private UUID newCourse(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, ?, 'PUBLISHED')",
                id, orgId, "override-" + id, "Runway Maths");
        return id;
    }

    private void enrol(UUID userId, UUID courseId, int progressPct) {
        jdbc.update("INSERT INTO enrollment (user_id, course_id, progress_pct) VALUES (?, ?, ?)",
                userId, courseId, progressPct);
    }

    private UUID newPipeline() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, ?, 'PUBLISHED')",
                id, "Override Pipeline " + id);
        return id;
    }

    private UUID newPillar(UUID pipelineId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pillars (id, pipeline_id, name, type, weight, display_order)
                VALUES (?, ?, 'Vision Clarity', 'STANDARD', 1, 0)
                """, id, pipelineId);
        return id;
    }

    private UUID newEvaluatedSubmission(UUID pipelineId, UUID orgId, UUID userId) {
        UUID assignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignment, pipelineId, orgId, userId, userId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status)
                VALUES (?, ?, ?, 'EVALUATED')
                """, id, assignment, userId);
        return id;
    }

    private String enrolmentStatus(UUID userId) {
        return jdbc.queryForObject("SELECT status FROM enrollment WHERE user_id = ? AND course_id = ?",
                String.class, userId, course);
    }

    private int progress(UUID userId) {
        return jdbc.queryForObject("SELECT progress_pct FROM enrollment WHERE user_id = ? AND course_id = ?",
                Integer.class, userId, course);
    }

    private int overrideCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM enrolment_overrides WHERE user_id = ?",
                Integer.class, userId);
    }
}
