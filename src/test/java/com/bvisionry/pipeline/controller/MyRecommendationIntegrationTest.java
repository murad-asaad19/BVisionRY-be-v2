package com.bvisionry.pipeline.controller;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/my/recommendations} against the real schema, through the real
 * filter chain.
 *
 * <p>Three layers, one test class, because all three are claims about the same
 * request:
 * <ul>
 *   <li>the ROUTE floor — an anonymous caller is refused before a handler is
 *       resolved, which is what {@link MvcResult#getHandler()} discriminates (a
 *       status code alone cannot tell a filter-chain refusal from a controller
 *       one);</li>
 *   <li>the DATA scope — a founder is served their own rows and only their own,
 *       proven by a SECOND founder with rows of their own rather than by the
 *       absence of anything to leak;</li>
 *   <li>the SQL itself — {@code findEnrolledByFounder} depends on the SCHEMA, not
 *       on catalog/enrollment Java types (the ArchUnit ratchet forbids the
 *       imports), so a renamed column would compile perfectly and fail at runtime.
 *       It has to meet real rows to be evidence.</li>
 * </ul>
 *
 * <p>{@code @Transactional} is fine here (unlike the engine's IT, whose central
 * claim is that it runs WITHOUT one) — but teardown is explicit anyway, because
 * the Postgres container is a singleton shared with every other IT in the JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class MyRecommendationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PATH = "/api/my/recommendations";

    @Autowired private MockMvc mockMvc;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Pillar vision;
    private UUID pipelineId;
    private UUID orgId;
    private UUID founder;
    private UUID otherFounder;
    private UUID submission;
    private UUID otherSubmission;
    private final List<UUID> courseIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> assignmentIds = new ArrayList<>();
    private final List<UUID> submissionIds = new ArrayList<>();
    private final Map<UUID, UUID> assignmentByUser = new HashMap<>();

    @BeforeEach
    void seed() {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Founder Readiness " + UUID.randomUUID());
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline.setPillars(new ArrayList<>(List.of(pillar(pipeline))));
        pipeline = pipelineRepository.save(pipeline);
        pipelineId = pipeline.getId();
        vision = pipeline.getPillars().getFirst();

        orgId = insertOrg();
        founder = insertFounder();
        otherFounder = insertFounder();
        submission = insertEvaluatedSubmission(founder);
        otherSubmission = insertEvaluatedSubmission(otherFounder);
    }

    @AfterEach
    void cleanUp() {
        userIds.forEach(id -> jdbc.update("DELETE FROM auto_enrolments WHERE user_id = ?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM enrollment WHERE user_id = ?", id));
        submissionIds.forEach(id -> jdbc.update("DELETE FROM submissions WHERE id = ?", id));
        assignmentIds.forEach(id -> jdbc.update("DELETE FROM assignments WHERE id = ?", id));
        courseIds.forEach(id -> jdbc.update("DELETE FROM course WHERE id = ?", id));
        jdbc.update("DELETE FROM pillars WHERE pipeline_id = ?", pipelineId);
        jdbc.update("DELETE FROM pipelines WHERE id = ?", pipelineId);
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
        courseIds.clear();
        userIds.clear();
        assignmentIds.clear();
        submissionIds.clear();
        assignmentByUser.clear();
    }

    // ------------------------------------------------------------------
    // Layer 1 — the route floor
    // ------------------------------------------------------------------

    @Test
    void anAnonymousCallerIsRefusedBeforeAnyControllerIsReached() throws Exception {
        MvcResult result = mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — the filter chain refused it, not the controller")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Layer 3 — the data scope
    // ------------------------------------------------------------------

    @Test
    void aFounderSeesTheCourseTheirAssessmentEnrolledThemInAndThePillarThatAskedForIt() throws Exception {
        UUID course = publishedCourse("Runway Maths", "runway-maths");
        enrol(founder, course);
        ledgerRow(founder, course, submission, "ENROLLED");

        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].courseId").value(course.toString()))
                .andExpect(jsonPath("$[0].courseTitle").value("Runway Maths"))
                .andExpect(jsonPath("$[0].courseSlug").value("runway-maths"))
                .andExpect(jsonPath("$[0].coursePublished").value(true))
                .andExpect(jsonPath("$[0].pillarName").value("Vision Clarity"))
                .andExpect(jsonPath("$[0].submissionId").value(submission.toString()));
    }

    @Test
    void aFounderNeverSeesAnotherFoundersRecommendation() throws Exception {
        UUID mine = publishedCourse("Mine", "mine-" + UUID.randomUUID());
        UUID theirs = publishedCourse("Theirs", "theirs-" + UUID.randomUUID());
        enrol(founder, mine);
        enrol(otherFounder, theirs);
        ledgerRow(founder, mine, submission, "ENROLLED");
        ledgerRow(otherFounder, theirs, otherSubmission, "ENROLLED");

        // The founder id is never in the request — there is nowhere to put one — so
        // this pins that the id used is the AUTHENTICATED one on both reads.
        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].courseId").value(mine.toString()));

        mockMvc.perform(get(PATH).with(authentication(principal(otherFounder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].courseId").value(theirs.toString()));
    }

    @Test
    void onlyEnrolledOutcomesAreRecommendations() throws Exception {
        // ALREADY_ENROLLED: the founder had it before this assessment ran, so the
        // engine did not give it to them. COURSE_NOT_PUBLISHED: nobody was enrolled
        // at all — the enrolment row below exists only to prove the OUTCOME is what
        // filters, not the enrolment join.
        UUID had = publishedCourse("Had It", "had-it-" + UUID.randomUUID());
        UUID refused = publishedCourse("Refused", "refused-" + UUID.randomUUID());
        enrol(founder, had);
        enrol(founder, refused);
        ledgerRow(founder, had, submission, "ALREADY_ENROLLED");
        ledgerRow(founder, refused, submission, "COURSE_NOT_PUBLISHED");

        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCourseUnpublishedAfterEnrolmentIsStillShownAndStillLinkable() throws Exception {
        // The engine only ever writes ENROLLED against a PUBLISHED course, so this
        // state can only arise afterwards. The founder still HAS the course — the
        // player is gated on enrolment, not on state — so dropping the row would
        // delete a recommendation instead of explaining it.
        String slug = "archived-" + UUID.randomUUID();
        UUID archived = course("Archived Course", slug, "ARCHIVED");
        enrol(founder, archived);
        ledgerRow(founder, archived, submission, "ENROLLED");

        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].coursePublished").value(false))
                // The link survives the unpublish — never a card with nowhere to go.
                .andExpect(jsonPath("$[0].courseSlug").value(slug));
    }

    @Test
    void aLedgerRowWhoseEnrolmentIsGoneIsNotShown() throws Exception {
        // The ledger records what WAS decided; the enrolment is what the founder
        // HAS. Recommending a course they are no longer on would be a dead card.
        UUID unenrolled = publishedCourse("Left It", "left-it-" + UUID.randomUUID());
        ledgerRow(founder, unenrolled, submission, "ENROLLED");
        // SOMEBODY ELSE is enrolled in it. Without this row the catalog join has a
        // course to find only when the WHOLE join is right, so dropping its
        // `AND e.user_id = :userId` term would still return nothing and this test
        // would stay green — the javadoc's "cannot be dropped by an edit that reads
        // innocuous" would be a claim with no test behind it. With the row, that
        // mutation serves the founder a course they are not on, and this reds.
        enrol(otherFounder, unenrolled);

        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theSameCourseRecommendedTwiceIsOneCardCarryingTheNewestDecision() throws Exception {
        UUID course = publishedCourse("Twice", "twice-" + UUID.randomUUID());
        UUID older = insertEvaluatedSubmission(founder);
        enrol(founder, course);
        ledgerRow(founder, course, older, "ENROLLED");
        ledgerRow(founder, course, submission, "ENROLLED");
        // createdAt is written by @PrePersist and both rows land in the same
        // millisecond band, so make the order unambiguous the way time would.
        jdbc.update("UPDATE auto_enrolments SET created_at = created_at - INTERVAL '1 day' "
                + "WHERE user_id = ? AND submission_id = ?", founder, older);

        mockMvc.perform(get(PATH).with(authentication(principal(founder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].submissionId").value(submission.toString()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The principal the JWT filter would install — the accessor reads it, never the path. */
    private Authentication principal(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@recommendations.invalid");
        user.setName("Founder");
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(UserRole.MEMBER.name())));
    }

    private static Pillar pillar(Pipeline pipeline) {
        Pillar pillar = new Pillar();
        pillar.setPipeline(pipeline);
        pillar.setName("Vision Clarity");
        pillar.setType(PillarType.STANDARD);
        pillar.setWeight(BigDecimal.ONE);
        pillar.setDisplayOrder(0);
        pillar.setMaturityThresholds(Map.of("Emerging", List.of(0, 59), "Strong", List.of(60, 100)));
        return pillar;
    }

    private UUID insertOrg() {
        Organization org = new Organization();
        org.setName("Recommendations Org " + UUID.randomUUID());
        return organizationRepository.saveAndFlush(org).getId();
    }

    private UUID insertFounder() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Founder', 'MEMBER', 'ACTIVE', ?)
                """, id, "recommendations." + id + "@test.invalid", orgId);
        userIds.add(id);
        return id;
    }

    /**
     * A second evaluated submission for the same founder reuses their assignment —
     * {@code uq_assignments_org_pipeline_user} allows exactly one, and re-assessment
     * is a new check-in on it rather than a new assignment.
     */
    private UUID insertEvaluatedSubmission(UUID userId) {
        UUID assignmentId = assignmentByUser.computeIfAbsent(userId, u -> {
            UUID newId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, newId, pipelineId, orgId, u, u);
            assignmentIds.add(newId);
            return newId;
        });
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status)
                VALUES (?, ?, ?, 'EVALUATED')
                """, id, assignmentId, userId);
        submissionIds.add(id);
        return id;
    }

    /** Own rows, not the V77 seed: the container is shared and some classes empty {@code course}. */
    private UUID publishedCourse(String title, String slug) {
        return course(title, slug, "PUBLISHED");
    }

    private UUID course(String title, String slug, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, ?, ?)",
                id, orgId, slug, title, state);
        courseIds.add(id);
        return id;
    }

    private void enrol(UUID userId, UUID courseId) {
        jdbc.update("INSERT INTO enrollment (user_id, course_id) VALUES (?, ?)", userId, courseId);
    }

    private void ledgerRow(UUID userId, UUID courseId, UUID submissionId, String outcome) {
        jdbc.update("""
                INSERT INTO auto_enrolments (user_id, course_id, submission_id, pillar_id,
                                             band_position, outcome)
                VALUES (?, ?, ?, ?, 0, ?)
                """, userId, courseId, submissionId, vision.getId(), outcome);
    }
}
