package com.bvisionry.pipeline.service;

import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.event.EvaluationEvents;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.AutoEnrolmentRepository;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * Auto-enrolment against the real schema, driven through the real event bus.
 *
 * <p>This is the only place the engine's raw SQL runs. Both
 * {@code FounderEnrolmentWriteRepository} and
 * {@code CourseCatalogReadRepository#findPublishedIds} depend on the SCHEMA rather
 * than on enrollment/catalog Java types (the ArchUnit ratchet forbids the imports),
 * so a renamed column or a dropped constraint would compile perfectly and fail at
 * runtime — {@code ON CONFLICT ON CONSTRAINT uq_enrollment_user_course} in
 * particular names a constraint no compiler can see. They have to meet real rows to
 * be evidence.
 *
 * <p>It also pins the wiring: the listener is reached by publishing the event, not
 * by calling the service, so a missing {@code @EventListener} fails here rather than
 * in production.
 *
 * <p><strong>Deliberately NOT {@code @Transactional}, unlike its sibling ITs.</strong>
 * The engine's central safety claim is that it runs with NO surrounding transaction,
 * so one failed course cannot poison the rest. A test-managed rollback transaction
 * inverts exactly that: every write joins one transaction, a constraint violation
 * aborts it, and the run ends in {@code UnexpectedRollbackException} — so the suite
 * would assert the property while demonstrating its opposite. The cost is explicit
 * teardown in {@link #cleanUp()} — the container is a singleton shared with every
 * other IT in the JVM, so nothing may be left behind.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class AutoEnrolmentIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ApplicationEventPublisher events;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Spied, not mocked, and only to stage the one thing a test cannot otherwise
     * reach: the instant between the idempotency read and the write. See
     * {@link #aMidLoopUniqueViolationDoesNotKillTheRemainingCourses()}.
     */
    @MockitoSpyBean private AutoEnrolmentRepository ledger;

    private Pillar vision;
    private UUID pipelineId;
    private UUID founderId;
    private UUID assignmentId;
    private UUID submissionId;
    private UUID publishedCourse;
    private UUID draftCourse;
    private final List<UUID> courseIds = new ArrayList<>();
    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Founder Readiness " + UUID.randomUUID());
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline.setPillars(new ArrayList<>(List.of(pillar(pipeline))));
        pipeline = pipelineRepository.save(pipeline);
        pipelineId = pipeline.getId();
        vision = pipeline.getPillars().getFirst();

        UUID orgId = insertOrg("Auto Enrolment Org");
        founderId = insertFounder(orgId);
        assignmentId = insertAssignment(pipelineId, orgId, founderId);
        submissionId = insertEvaluatedSubmission(assignmentId, founderId);

        publishedCourse = insertCourse(orgId, "PUBLISHED");
        draftCourse = insertCourse(orgId, "DRAFT");
    }

    /**
     * Child-first and explicit. Cascades would cover most of it, but leaning on them
     * would mean every future FK change silently decides whether this class leaks
     * into the container every other IT shares.
     */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM auto_enrolments WHERE user_id = ?", founderId);
        jdbc.update("DELETE FROM enrollment WHERE user_id = ?", founderId);
        jdbc.update("DELETE FROM pillar_evaluations WHERE submission_id = ?", submissionId);
        jdbc.update("DELETE FROM submissions WHERE id = ?", submissionId);
        jdbc.update("DELETE FROM assignments WHERE id = ?", assignmentId);
        jdbc.update("DELETE FROM pillar_course_mappings WHERE pillar_id = ?", vision.getId());
        courseIds.forEach(id -> jdbc.update("DELETE FROM course WHERE id = ?", id));
        jdbc.update("DELETE FROM pillars WHERE pipeline_id = ?", pipelineId);
        jdbc.update("DELETE FROM pipelines WHERE id = ?", pipelineId);
        jdbc.update("DELETE FROM users WHERE id = ?", founderId);
        orgIds.forEach(id -> jdbc.update("DELETE FROM organizations WHERE id = ?", id));
        courseIds.clear();
        orgIds.clear();
    }

    @Test
    void enrolsTheFounderInTheBandsPublishedCourseAndStoresWhichPillarAskedForIt() {
        // "Emerging" is 0-59 — position 0, the weakest band.
        mapCourse(0, publishedCourse);
        storeMaturityLabel("Emerging");

        publish();

        assertThat(enrolmentCount(publishedCourse)).isEqualTo(1);
        Map<String, Object> ledgerRow = ledgerRow(publishedCourse);
        assertThat(ledgerRow.get("outcome")).isEqualTo("ENROLLED");
        assertThat(ledgerRow.get("pillar_id")).isEqualTo(vision.getId());
        assertThat(ledgerRow.get("band_position")).isEqualTo(0);
        assertThat(ledgerRow.get("submission_id")).isEqualTo(submissionId);
    }

    @Test
    void enrolsAcrossOrgBoundariesBecauseThePublishedCatalogIsPlatformWide() {
        // The load-bearing half of the ownership ruling: a platform-declared mapping ->
        // an org's founder -> a course a DIFFERENT org wrote. "The public catalog
        // returns ALL PUBLISHED courses regardless of which org created them"
        // (CourseRepository), so this is the intended shape rather than a leak — and
        // `enrollment` carries no org column that could disagree with it.
        UUID otherOrgCourse = insertCourse(insertOrg("Someone Else's Catalog"), "PUBLISHED");
        mapCourse(0, otherOrgCourse);
        storeMaturityLabel("Emerging");

        publish();

        assertThat(enrolmentCount(otherOrgCourse)).isEqualTo(1);
        assertThat(ledgerRow(otherOrgCourse).get("outcome")).isEqualTo("ENROLLED");
    }

    @Test
    void refusesADraftCourseAndSaysSoWithoutTouchingTheEnrolmentTable() {
        mapCourse(0, draftCourse);
        storeMaturityLabel("Emerging");

        publish();

        assertThat(enrolmentCount(draftCourse)).isZero();
        assertThat(ledgerRow(draftCourse).get("outcome")).isEqualTo("COURSE_NOT_PUBLISHED");
    }

    @Test
    void reProcessingTheSameEvaluationChangesNothing() {
        mapCourse(0, publishedCourse);
        storeMaturityLabel("Emerging");

        publish();
        publish();

        // uq_enrollment_user_course and uq_auto_enrolments both hold; neither the
        // enrolment nor the reason is duplicated, and nothing threw.
        assertThat(enrolmentCount(publishedCourse)).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void leavesAnEnrolmentTheFounderAlreadyHasExactlyAsItWas() {
        // The founder is 40% through this course from an earlier evaluation or their
        // own enrolment. NEW_ENROLMENTS_ONLY: progress must survive untouched.
        jdbc.update("INSERT INTO enrollment (user_id, course_id, progress_pct) VALUES (?, ?, 40)",
                founderId, publishedCourse);
        mapCourse(0, publishedCourse);
        storeMaturityLabel("Emerging");

        publish();

        assertThat(jdbc.queryForObject(
                "SELECT progress_pct FROM enrollment WHERE user_id = ? AND course_id = ?",
                Integer.class, founderId, publishedCourse)).isEqualTo(40);
        assertThat(ledgerRow(publishedCourse).get("outcome")).isEqualTo("ALREADY_ENROLLED");
    }

    @Test
    void aStoredLabelThatNamesNoCurrentBandEnrolsNobody() {
        mapCourse(0, publishedCourse);
        // What a failed pillar evaluation stores — and what a band renamed after the
        // founder was measured looks like.
        storeMaturityLabel("Unknown");

        publish();

        assertThat(enrolmentCount(publishedCourse)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    /**
     * Course ids are PINNED, not random, and the order is the whole test.
     *
     * <p>The engine walks a band's rules {@code ORDER BY course_id ASC}, so the
     * failing course has to sort FIRST for anything to remain after it — with random
     * ids it lands last half the time, and a run where nothing follows the failure
     * proves nothing. (Verified: pinned last, deleting per-course isolation outright
     * leaves this green.)
     *
     * <p>They differ only in the final byte so the two orderings that matter agree.
     * Postgres compares {@code uuid} as unsigned bytes; {@code java.util.UUID}
     * compares as two SIGNED longs, and those disagree for ids straddling a sign bit
     * — a trap worth stating, because a "random but sorted in Java" fixture would
     * reintroduce exactly the coin flip this replaces.
     */
    private static final UUID CONFLICTING_COURSE = UUID.fromString("00000000-0000-4000-8000-00000000a001");
    private static final UUID SURVIVING_COURSE = UUID.fromString("00000000-0000-4000-8000-00000000a002");

    @Test
    void aMidLoopUniqueViolationDoesNotKillTheRemainingCourses() {
        // Stage the documented race exactly. A competing worker writes the ledger row
        // for the FIRST course after we read the ledger but before we write it: only
        // the READ is faked here (it returns what it would have seen a millisecond
        // earlier), and the write below meets the real uq_auto_enrolments index and
        // raises a real DataIntegrityViolationException with a course still to come.
        UUID catalogOrg = insertOrg("Second Catalog");
        insertCourse(CONFLICTING_COURSE, catalogOrg, "PUBLISHED");
        insertCourse(SURVIVING_COURSE, catalogOrg, "PUBLISHED");
        mapCourse(0, CONFLICTING_COURSE);
        mapCourse(0, SURVIVING_COURSE);
        storeMaturityLabel("Emerging");
        insertLedgerRow(CONFLICTING_COURSE);
        doReturn(List.of()).when(ledger).findBySubmissionIdAndUserId(any(), any());

        publish();

        // The point: with no surrounding transaction the aborted statement is its own,
        // so the loop carries on and the founder still gets the later course. Two
        // mutations red this, and neither is the obvious one:
        //   · rethrow from the per-course DataIntegrityViolationException catch —
        //     isolation deleted, the survivor never lands;
        //   · @Transactional on onSubmissionEvaluated — the PROXIED entry point, so
        //     one transaction wraps the whole loop and the run dies with
        //     UnexpectedRollbackException ("marked as rollback-only"), not a raw
        //     25P02. Annotating enrol() instead does nothing at all: it is
        //     self-invoked, so the proxy is bypassed — the same no-op
        //     AutoEnrolmentService#record documents.
        assertThat(enrolmentCount(SURVIVING_COURSE)).isEqualTo(1);
        assertThat(ledgerRow(SURVIVING_COURSE).get("outcome")).isEqualTo("ENROLLED");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void publish() {
        events.publishEvent(new EvaluationEvents.SubmissionEvaluated(
                submissionId, founderId, Map.of(vision.getId(), storedLabel())));
    }

    private String storedLabel() {
        return jdbc.queryForObject(
                "SELECT maturity_label FROM pillar_evaluations WHERE submission_id = ? AND pillar_id = ?",
                String.class, submissionId, vision.getId());
    }

    private void storeMaturityLabel(String label) {
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, 42.0, ?)
                """, submissionId, vision.getId(), label);
    }

    private void mapCourse(int bandPosition, UUID courseId) {
        jdbc.update("""
                INSERT INTO pillar_course_mappings (pillar_id, band_position, course_id)
                VALUES (?, ?, ?)
                """, vision.getId(), bandPosition, courseId);
    }

    /** The competing worker's row — the same idempotency key the engine is about to use. */
    private void insertLedgerRow(UUID courseId) {
        jdbc.update("""
                INSERT INTO auto_enrolments (user_id, course_id, submission_id, pillar_id,
                                             band_position, outcome)
                VALUES (?, ?, ?, ?, 0, 'ENROLLED')
                """, founderId, courseId, submissionId, vision.getId());
    }

    private static Pillar pillar(Pipeline pipeline) {
        Pillar pillar = new Pillar();
        pillar.setPipeline(pipeline);
        pillar.setName("Vision Clarity");
        pillar.setType(PillarType.STANDARD);
        pillar.setWeight(BigDecimal.ONE);
        pillar.setDisplayOrder(0);
        pillar.setMaturityThresholds(Map.of(
                "Elite", List.of(80, 100),
                "Emerging", List.of(0, 59),
                "Strong", List.of(60, 79)));
        return pillar;
    }

    private UUID insertOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        UUID id = organizationRepository.saveAndFlush(org).getId();
        orgIds.add(id);
        return id;
    }

    private UUID insertFounder(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Founder', 'MEMBER', 'ACTIVE', ?)
                """, id, "auto-enrol." + id + "@test.invalid", orgId);
        return id;
    }

    private UUID insertAssignment(UUID pipelineId, UUID orgId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, id, pipelineId, orgId, userId, userId);
        return id;
    }

    private UUID insertEvaluatedSubmission(UUID assignmentId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status)
                VALUES (?, ?, ?, 'EVALUATED')
                """, id, assignmentId, userId);
        return id;
    }

    /** Own rows, not the V77 seed: the container is shared and some classes empty {@code course}. */
    private UUID insertCourse(UUID orgId, String state) {
        return insertCourse(UUID.randomUUID(), orgId, state);
    }

    /** As above, with the id chosen — the one test whose assertion depends on sort order. */
    private UUID insertCourse(UUID id, UUID orgId, String state) {
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, ?, ?)",
                id, orgId, "auto-enrol-" + id, "Runway Maths", state);
        courseIds.add(id);
        return id;
    }

    private int enrolmentCount(UUID courseId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM enrollment WHERE user_id = ? AND course_id = ?",
                Integer.class, founderId, courseId);
    }

    private int ledgerCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auto_enrolments WHERE submission_id = ?",
                Integer.class, submissionId);
    }

    private Map<String, Object> ledgerRow(UUID courseId) {
        return jdbc.queryForMap(
                "SELECT * FROM auto_enrolments WHERE submission_id = ? AND course_id = ?",
                submissionId, courseId);
    }
}
