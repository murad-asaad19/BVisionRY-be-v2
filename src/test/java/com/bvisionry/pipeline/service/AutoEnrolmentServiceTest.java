package com.bvisionry.pipeline.service;

import com.bvisionry.common.event.EvaluationEvents.SubmissionEvaluated;
import com.bvisionry.pipeline.entity.AutoEnrolment;
import com.bvisionry.pipeline.entity.AutoEnrolmentOutcome;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.PillarCourseMapping;
import com.bvisionry.pipeline.repository.AutoEnrolmentRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository;
import com.bvisionry.pipeline.repository.EnrolmentOverrideReadRepository;
import com.bvisionry.pipeline.repository.FounderEnrolmentWriteRepository;
import com.bvisionry.pipeline.repository.PillarCourseMappingRepository;
import com.bvisionry.pipeline.repository.PillarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The behaviours that decide whether a real founder gets a real enrolment. Each
 * test pins one rule the ticket states, because every one of them is silent when
 * it breaks — nothing on screen says "you were enrolled off the wrong band".
 */
@ExtendWith(MockitoExtension.class)
class AutoEnrolmentServiceTest {

    @Mock private PillarRepository pillarRepository;
    @Mock private PillarCourseMappingRepository mappingRepository;
    @Mock private CourseCatalogReadRepository courseCatalog;
    @Mock private AutoEnrolmentRepository ledger;
    @Mock private FounderEnrolmentWriteRepository founderEnrolments;
    @Mock private EnrolmentOverrideReadRepository overrides;
    @Mock private com.bvisionry.common.coursevisibility.CourseVisibilityAccess courseVisibility;

    private AutoEnrolmentService service;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final UUID submissionId = UUID.randomUUID();
    private final UUID founderId = UUID.randomUUID();
    private final UUID pillarId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private Pillar pillar;

    @BeforeEach
    void setUp() {
        service = new AutoEnrolmentService(
                pillarRepository, mappingRepository, courseCatalog, ledger, founderEnrolments,
                overrides, courseVisibility, meterRegistry);

        // Spec §3 visibility is a new pre-filter in front of every decision;
        // these tests are about the BAND rules, so nothing is hidden here. The
        // "invisible course is skipped" behaviour has its own test below.
        lenient().when(courseVisibility.filterVisibleForUser(eq(founderId), anyCollection()))
                .thenAnswer(inv -> new java.util.HashSet<>(inv.getArgument(1, java.util.Collection.class)));

        pillar = pillar("Vision Clarity", 1);
    }

    // ------------------------------------------------------------------
    // The happy path, and the reason it is stored
    // ------------------------------------------------------------------

    @Test
    void enrolsTheCoursesTheStoredLabelsBandMapsToAndRecordsThatPillarAsTheReason() {
        stubPillars(pillar);
        // "Emerging" is 0-59 — the WEAKEST band, so position 0. Note the map is
        // deliberately not in ascending key order: position comes from sorting on
        // minimum score, never from jsonb's iteration order.
        stubRules(pillarId, 0, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, courseId)).thenReturn(true);

        service.enrol(event(Map.of(pillarId, "Emerging")));

        verify(founderEnrolments).enrolIfAbsent(founderId, courseId);
        AutoEnrolment written = captureLedgerRow();
        assertThat(written.getOutcome()).isEqualTo(AutoEnrolmentOutcome.ENROLLED);
        assertThat(written.getUserId()).isEqualTo(founderId);
        assertThat(written.getCourseId()).isEqualTo(courseId);
        assertThat(written.getSubmissionId()).isEqualTo(submissionId);
        // The WHY: dashboard_recommendations reads these two to say
        // "recommended because of <pillar>".
        assertThat(written.getPillarId()).isEqualTo(pillarId);
        assertThat(written.getBandPosition()).isZero();
    }

    @Test
    void resolvesTheBandByMinimumScoreOrderNotByTheOrderTheLabelsHappenToBeStoredIn() {
        stubPillars(pillar);
        // "Strong" is 60-79: the MIDDLE band by score, so position 1 — even though
        // the map hands it back last. A rule stored at position 1 is the one that
        // must fire, and nothing else.
        stubRules(pillarId, 1, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, courseId)).thenReturn(true);

        service.enrol(event(Map.of(pillarId, "Strong")));

        verify(mappingRepository).findByPillarIdAndBandPositionOrderByCourseIdAsc(pillarId, 1);
        assertThat(captureLedgerRow().getBandPosition()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // The three things that must NOT happen
    // ------------------------------------------------------------------

    @Test
    void anOffAxisStoredLabelTriggersNothingAtAllForThatPillar() {
        stubPillars(pillar);

        // "Unknown" is what a FAILED pillar evaluation stores, and it names no band.
        // A re-labelled band set produces the same shape. Either way the engine must
        // not guess a neighbouring band — and must not reach the rules at all.
        service.enrol(event(Map.of(pillarId, "Unknown")));

        verifyNoInteractions(mappingRepository, courseCatalog, founderEnrolments, ledger);
    }

    @Test
    void refusesANonPublishedCourseAndRecordsWhyInsteadOfEnrollingOrThrowing() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        // The course is DRAFT or ARCHIVED, so it is absent from the published set.
        // The mapping surface does not filter by state — this refusal is ours.
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of());
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());

        service.enrol(event(Map.of(pillarId, "Emerging")));

        verify(founderEnrolments, never()).enrolIfAbsent(any(), any());
        assertThat(captureLedgerRow().getOutcome())
                .isEqualTo(AutoEnrolmentOutcome.COURSE_NOT_PUBLISHED);
    }

    @Test
    void reProcessingTheSameEvaluationIsANoOp() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        // The idempotency key is [founder, course, evaluation]; founder and evaluation
        // are fixed for this run, so a ledger row for the course means "decided".
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of(decided(courseId)));

        service.enrol(event(Map.of(pillarId, "Emerging")));

        verify(founderEnrolments, never()).enrolIfAbsent(any(), any());
        verify(ledger, never()).saveAndFlush(any());
    }

    @Test
    void neverDuplicatesAnEnrolmentTheFounderAlreadyHasButStillRecordsTheDecision() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        // A DIFFERENT evaluation (or the founder themselves) already enrolled them.
        // NEW_ENROLMENTS_ONLY: the existing row and its progress are left alone.
        when(founderEnrolments.enrolIfAbsent(founderId, courseId)).thenReturn(false);

        service.enrol(event(Map.of(pillarId, "Emerging")));

        assertThat(captureLedgerRow().getOutcome())
                .isEqualTo(AutoEnrolmentOutcome.ALREADY_ENROLLED);
    }

    // ------------------------------------------------------------------
    // The admin override (roadmap §7 item 10)
    // ------------------------------------------------------------------

    /**
     * THE falsifying case for the whole ticket.
     *
     * <p>Every input here says "enrol them": the band maps to the course, the course
     * is PUBLISHED, and the ledger read comes back EMPTY — which is what a NEW
     * evaluation always sees, because the idempotency key includes the submission id
     * and this submission has never been processed. Before the override existed
     * that combination re-enrolled the founder in a course an admin had just removed
     * them from, and nothing anywhere said so. The override table is the only reason
     * this now enrols nobody.
     */
    @Test
    void aCourseAnAdminRemovedIsNotReEnrolledByAFreshEvaluation() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        when(overrides.findCourseIdsFor(founderId)).thenReturn(Set.of(courseId));

        service.enrol(event(Map.of(pillarId, "Emerging")));

        verify(founderEnrolments, never()).enrolIfAbsent(any(), any());
        // And no ledger row: a fourth outcome would need the V151 CHECK widened,
        // which that migration reserves to a human. The override row is the record.
        verify(ledger, never()).saveAndFlush(any());
    }

    @Test
    void anOverrideRemovesOneCourseAndLeavesTheRestOfTheJourneyIntact() {
        Pillar second = pillar("Runway", 2);
        UUID keptCourse = UUID.randomUUID();
        stubPillars(pillar, second);
        stubRules(pillarId, 0, courseId);
        stubRules(second.getId(), 0, keptCourse);
        when(overrides.findCourseIdsFor(founderId)).thenReturn(Set.of(courseId));
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId, keptCourse));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, keptCourse)).thenReturn(true);

        service.enrol(event(labels(pillarId, "Emerging", second.getId(), "Emerging")));

        verify(founderEnrolments, never()).enrolIfAbsent(founderId, courseId);
        verify(founderEnrolments).enrolIfAbsent(founderId, keptCourse);
        assertThat(captureLedgerRow().getCourseId()).isEqualTo(keptCourse);
    }

    // ------------------------------------------------------------------
    // Failure isolation
    // ------------------------------------------------------------------

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void oneBadCourseCostsThatCourseAndNothingElse() {
        Pillar second = pillar("Runway", 2);
        UUID goodCourse = UUID.randomUUID();
        stubPillars(pillar, second);
        stubRules(pillarId, 0, courseId);
        stubRules(second.getId(), 0, goodCourse);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId, goodCourse));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, courseId))
                .thenThrow(new IllegalStateException("course row vanished mid-flight"));
        when(founderEnrolments.enrolIfAbsent(founderId, goodCourse)).thenReturn(true);

        service.enrol(event(labels(pillarId, "Emerging", second.getId(), "Emerging")));

        // The second pillar's course still lands.
        verify(founderEnrolments).enrolIfAbsent(founderId, goodCourse);
        verify(ledger).saveAndFlush(any());
        // ...and the loss is VISIBLE. Nothing re-drives a clean EVALUATED submission,
        // so this counter is the only trace that a founder is missing a course.
        assertThat(decisionsFailed("error")).isEqualTo(1.0);
        assertThat(decisionsFailed("conflict")).isZero();
    }

    @Test
    void aConcurrentDuplicateIsCountedAsTheExpectedRaceNotAsAnError() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, courseId)).thenReturn(true);
        // Another worker decided this course between our ledger read and our write, and
        // uq_auto_enrolments settled it. Benign — their row IS the decision — but the
        // two are counted apart so a real loss is not buried under expected noise.
        when(ledger.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_auto_enrolments"));

        service.enrol(event(Map.of(pillarId, "Emerging")));

        assertThat(decisionsFailed("conflict")).isEqualTo(1.0);
        assertThat(decisionsFailed("error")).isZero();
    }

    @Test
    void theListenerNeverThrowsBackIntoTheEvaluationThatPublishedIt() {
        when(pillarRepository.findAllById(anyCollection()))
                .thenThrow(new IllegalStateException("database is on fire"));

        // An evaluation's success must never depend on enrolment succeeding.
        assertThatCode(() -> service.onSubmissionEvaluated(event(Map.of(pillarId, "Emerging"))))
                .doesNotThrowAnyException();
        // A whole journey is gone and no path re-drives it, so it is counted.
        assertThat(meterRegistry.counter("bvisionry.auto_enrolment.journey_failed").count())
                .isEqualTo(1.0);
    }

    private double decisionsFailed(String kind) {
        return meterRegistry.counter("bvisionry.auto_enrolment.decision_failed", "kind", kind).count();
    }

    // ------------------------------------------------------------------
    // The idempotency key admits exactly one reason per course
    // ------------------------------------------------------------------

    @Test
    void twoPillarsMappingTheSameCourseProduceOneDecisionAttributedToTheEarlierPillar() {
        Pillar later = pillar("Runway", 2);
        // Returned in the "wrong" order on purpose: the engine sorts by displayOrder
        // so the recorded reason is the pillar the founder answered first, not
        // whatever the database felt like returning.
        stubPillars(later, pillar);
        stubRules(pillarId, 0, courseId);
        stubRules(later.getId(), 0, courseId);
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of(courseId));
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(founderEnrolments.enrolIfAbsent(founderId, courseId)).thenReturn(true);

        service.enrol(event(labels(pillarId, "Emerging", later.getId(), "Emerging")));

        // One enrolment, one ledger row — the key is [founder, course, evaluation].
        verify(founderEnrolments).enrolIfAbsent(founderId, courseId);
        assertThat(captureLedgerRow().getPillarId()).isEqualTo(pillarId);
    }

    @Test
    void anEvaluationWithNoPillarRowsDoesNothing() {
        service.enrol(event(Map.of()));

        verifyNoInteractions(pillarRepository, mappingRepository, courseCatalog, ledger, founderEnrolments);
    }

    // ------------------------------------------------------------------
    // Spec §3: Suggest mode, and the visibility pre-filter
    // ------------------------------------------------------------------

    @Test
    void suggestModeRecordsASuggestionAndEnrolsNobody() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId, com.bvisionry.pipeline.entity.PillarCourseMode.SUGGEST);
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());

        service.enrol(event(Map.of(pillarId, "Emerging")));

        // The offer is the ledger row; there is no seat until the founder accepts.
        verifyNoInteractions(founderEnrolments);
        AutoEnrolment row = captureLedgerRow();
        assertThat(row.getOutcome()).isEqualTo(AutoEnrolmentOutcome.SUGGESTED);
        assertThat(row.getAcceptedAt()).isNull();
        assertThat(row.getPillarId()).isEqualTo(pillarId);
    }

    @Test
    void suggestModeDoesNotEvenAskWhetherTheCourseIsPublished() {
        // Deliberate: refusing to suggest a course that publishes tomorrow would
        // lose the reason for good, because nothing re-drives an evaluation.
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId, com.bvisionry.pipeline.entity.PillarCourseMode.SUGGEST);
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(courseCatalog.findPublishedIds(anyCollection())).thenReturn(Set.of());

        service.enrol(event(Map.of(pillarId, "Emerging")));

        assertThat(captureLedgerRow().getOutcome()).isEqualTo(AutoEnrolmentOutcome.SUGGESTED);
    }

    @Test
    void aCourseTheFoundersOrgCannotSeeIsSkippedSilently_noEnrolmentAndNoLedgerRow() {
        stubPillars(pillar);
        stubRules(pillarId, 0, courseId);
        when(ledger.findBySubmissionIdAndUserId(submissionId, founderId)).thenReturn(List.of());
        when(courseVisibility.filterVisibleForUser(eq(founderId), anyCollection())).thenReturn(Set.of());

        service.enrol(event(Map.of(pillarId, "Emerging")));

        // Spec §3: "AI rules silently skip courses the org can't see" — silently
        // means no row either, so nothing can leak the course's existence.
        verifyNoInteractions(founderEnrolments);
        verify(ledger, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Pillar pillar(String name, int displayOrder) {
        Pillar p = new Pillar();
        p.setId(name.equals("Vision Clarity") ? pillarId : UUID.randomUUID());
        p.setName(name);
        p.setDisplayOrder(displayOrder);
        Map<String, List<Integer>> thresholds = new LinkedHashMap<>();
        thresholds.put("Elite", List.of(80, 100));
        thresholds.put("Emerging", List.of(0, 59));
        thresholds.put("Strong", List.of(60, 79));
        p.setMaturityThresholds(thresholds);
        return p;
    }

    private void stubPillars(Pillar... pillars) {
        when(pillarRepository.findAllById(anyCollection())).thenReturn(List.of(pillars));
    }

    private void stubRules(UUID pillar, int bandPosition, UUID course) {
        stubRules(pillar, bandPosition, course, com.bvisionry.pipeline.entity.PillarCourseMode.AUTO_ASSIGN);
    }

    private void stubRules(UUID pillar, int bandPosition, UUID course,
                           com.bvisionry.pipeline.entity.PillarCourseMode mode) {
        PillarCourseMapping rule = new PillarCourseMapping();
        rule.setBandPosition(bandPosition);
        rule.setCourseId(course);
        rule.setMode(mode);
        when(mappingRepository.findByPillarIdAndBandPositionOrderByCourseIdAsc(eq(pillar), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1, Integer.class) == bandPosition
                        ? List.of(rule)
                        : List.of());
    }

    private AutoEnrolment decided(UUID course) {
        AutoEnrolment row = new AutoEnrolment();
        row.setUserId(founderId);
        row.setCourseId(course);
        row.setSubmissionId(submissionId);
        return row;
    }

    private SubmissionEvaluated event(Map<UUID, String> labels) {
        return new SubmissionEvaluated(submissionId, founderId, labels);
    }

    private static Map<UUID, String> labels(UUID first, String firstLabel, UUID second, String secondLabel) {
        Map<UUID, String> labels = new LinkedHashMap<>();
        labels.put(first, firstLabel);
        labels.put(second, secondLabel);
        return labels;
    }

    private AutoEnrolment captureLedgerRow() {
        ArgumentCaptor<AutoEnrolment> captor = ArgumentCaptor.forClass(AutoEnrolment.class);
        verify(ledger).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
