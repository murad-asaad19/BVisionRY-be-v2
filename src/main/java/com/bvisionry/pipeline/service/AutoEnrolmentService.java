package com.bvisionry.pipeline.service;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.event.EvaluationEvents.SubmissionEvaluated;
import com.bvisionry.pipeline.entity.AutoEnrolment;
import com.bvisionry.pipeline.entity.AutoEnrolmentOutcome;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.PillarCourseMapping;
import com.bvisionry.pipeline.entity.PillarCourseMode;
import com.bvisionry.pipeline.repository.AutoEnrolmentRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository;
import com.bvisionry.pipeline.repository.EnrolmentOverrideReadRepository;
import com.bvisionry.pipeline.repository.FounderEnrolmentWriteRepository;
import com.bvisionry.pipeline.repository.PillarCourseMappingRepository;
import com.bvisionry.pipeline.repository.PillarRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Personalized learning journeys" (roadmap §7 item 10): when a founder's
 * evaluation completes, put them on the courses their weakest bands call for.
 *
 * <p>Consumes the config {@code PillarCourseMappingService} writes. That surface is
 * declarative and never enrols anyone; this is the only thing that does.
 *
 * <h2>Trigger, and why it is a sound one</h2>
 * A {@link SubmissionEvaluated} event, published by the {@code evaluation} slice
 * once {@code persistEvaluationOutcome} has COMMITTED and only for a clean
 * {@code EVALUATED} run. So the enrolments a founder gets can never disagree with
 * the results they are looking at, and a degraded run — whose failed pillars carry
 * the label {@code "Unknown"} — enrols nothing until its retry succeeds.
 *
 * <h2>Synchronous, on the evaluation's own async thread</h2>
 * Not {@code @Async}, not a scheduled sweep. The whole evaluation already runs on
 * {@code evaluationExecutor}, and every existing post-evaluation side effect (the
 * org audit entry, the results-ready email, the push notification) runs inline on
 * that thread inside {@code recordEvaluationSideEffects}, which the caller wraps in
 * a catch so "results already saved" survives any of them failing. Riding that
 * harness gets the isolation the ticket demands without inventing a second async
 * layer, and the founder's journey is ready the moment their results are.
 *
 * <p>A sweep was the alternative and was rejected on blast radius, not taste: a job
 * that finds "EVALUATED submissions with no ledger rows" matches every submission
 * ever evaluated, so its first tick would bulk-enrol the entire historical user
 * base unless someone remembered a watermark. An event only ever fires forward.
 *
 * <h2>Nothing here can fail an evaluation</h2>
 * Three nested guards, because this writes to real founders' accounts:
 * <ol>
 *   <li>{@link #onSubmissionEvaluated} catches everything, so the listener never
 *       throws back into the publisher;</li>
 *   <li>each (pillar, course) decision is caught individually, so one bad rule,
 *       deleted course or constraint clash costs that course and nothing else;</li>
 *   <li>no surrounding transaction — each write is its own short one, so a caught
 *       failure cannot mark a shared transaction rollback-only and quietly discard
 *       the enrolments that already succeeded.</li>
 * </ol>
 *
 * <h2>What those guards cost, stated plainly: nothing is ever retried</h2>
 * A clean {@code EVALUATED} submission is never re-driven — the reaper recovers
 * {@code SUBMITTED} rows and the retry paths need {@code FAILED} or
 * {@code NEEDS_REVIEW}, so no path re-visits a submission this engine has already
 * seen. Every caught failure above is therefore a PERMANENT silent non-write,
 * healed only by the founder taking a NEW assessment under a new submission id.
 * That is the deliberate trade for "an evaluation must never fail because an
 * enrolment did", and it is why both catches increment a counter
 * ({@code bvisionry.auto_enrolment.decision_failed} /
 * {@code .journey_failed}): an operator cannot be told about a silent loss by a
 * log line nobody greps for. A re-drive would be the fix and is a separate,
 * deliberately unshipped decision — an admin-triggered re-run, not a sweep.
 *
 * <h2>Idempotency</h2>
 * Exactly {@code [founder_id, course_id, source_evaluation_id]} (agent-policy
 * {@code auto_enrolment_idempotency_key}), enforced twice: the ledger read below
 * skips anything this evaluation already decided, and the unique index in V151
 * settles two workers racing the same submission. A NEW evaluation carries a new
 * submission id, so it may add courses — but never a second enrolment in one the
 * founder already has, and never an unenrolment
 * ({@code auto_enrolment_on_reassessment: NEW_ENROLMENTS_ONLY}).
 *
 * <h2>The admin override, and why it could not be a ledger row</h2>
 * The key above is the reason {@code enrolment_overrides} (V157) is a separate
 * table rather than a fourth {@link AutoEnrolmentOutcome}. Per-submission grain
 * is exactly wrong for an override: a re-assessment carries a new submission id,
 * finds no ledger row for it, and would cheerfully re-enrol the founder in the
 * course an admin just removed. The override table is keyed
 * {@code (user_id, course_id)} with no submission at all, so it outlives every
 * evaluation — and this engine consults it FIRST, before the per-evaluation
 * idempotency read, because "an admin said no" outranks "this evaluation has not
 * decided yet".
 *
 * <p>An overridden course writes NO ledger row — the same silence an off-axis
 * pillar produces. Recording it would need a fourth {@code outcome} value, and
 * V151 pins that set with a CHECK it explicitly reserves widening to a human
 * ({@code never_auto_decide}). The override row is the record instead, and a
 * better one: it carries who removed them, when, and why in their own words.
 *
 * <h2>No endpoint</h2>
 * Engine and data only. There is no controller, no DTO and no route, so there is no
 * new authorization surface to defend — the only entry point is an in-process event
 * published by a slice that already ran the founder's evaluation. The founder-facing
 * read of these rows belongs to {@code dashboard_recommendations}, and its handler
 * brings its own three layers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoEnrolmentService {

    private final PillarRepository pillarRepository;
    private final PillarCourseMappingRepository mappingRepository;
    private final CourseCatalogReadRepository courseCatalog;
    private final AutoEnrolmentRepository ledger;
    private final FounderEnrolmentWriteRepository founderEnrolments;
    private final EnrolmentOverrideReadRepository overrides;
    private final CourseVisibilityAccess courseVisibility;
    private final MeterRegistry meterRegistry;

    /**
     * One (pillar, course) decision was lost. {@code kind=conflict} is the expected
     * concurrent-duplicate race and is benign; {@code kind=error} is not, and means a
     * founder is missing a course nothing will ever retry.
     */
    private static final String DECISION_FAILED = "bvisionry.auto_enrolment.decision_failed";

    /** A founder's WHOLE journey was lost for one evaluation. Never benign. */
    private static final String JOURNEY_FAILED = "bvisionry.auto_enrolment.journey_failed";

    /** One course a pillar's band asks for, with the reason and the mode attached. */
    private record Candidate(UUID pillarId, String pillarName, int bandPosition, UUID courseId,
                             PillarCourseMode mode) {
    }

    /**
     * The outer guard. An evaluation's success must never depend on enrolment
     * succeeding, so nothing escapes here — a failure is loud in the log and costs
     * this founder their auto-enrolment, not their results.
     */
    @EventListener
    public void onSubmissionEvaluated(SubmissionEvaluated event) {
        try {
            enrol(event);
        } catch (RuntimeException e) {
            // Everything before the per-course loop — the ledger read, the pillar load,
            // the published-course lookup — fails the founder's whole journey, and
            // nothing re-drives it. The counter is the only trace an operator gets.
            meterRegistry.counter(JOURNEY_FAILED).increment();
            log.error("Auto-enrolment failed for submission {} (founder {}); the evaluation is "
                    + "unaffected, but this founder's journey is permanently unwritten "
                    + "until they are re-assessed: {}",
                    event.submissionId(), event.founderId(), e.getMessage(), e);
        }
    }

    /**
     * Resolve every pillar's band from its STORED label, look up that band's rules
     * and record a decision per course.
     *
     * <p>Package-private so a test can drive it without the event bus; the event is
     * the only production caller.
     */
    void enrol(SubmissionEvaluated event) {
        Map<UUID, String> labels = event.maturityLabelByPillar();
        if (labels.isEmpty()) {
            return;
        }

        List<Candidate> candidates = resolveCandidates(labels);
        if (candidates.isEmpty()) {
            return;
        }

        // Courses an admin has taken this founder off (V157). Read once per
        // evaluation, ahead of everything else, because it is the only input here
        // that a HUMAN authored: it is not scoped to a submission, so unlike the
        // ledger read below it still speaks for assessments taken years later.
        Set<UUID> overridden = overrides.findCourseIdsFor(event.founderId());

        // Already decided by an earlier pass over THIS evaluation — the idempotent
        // skip. Outcome is irrelevant: a course refused as DRAFT last time stays
        // refused, so a re-run neither re-refuses it nor quietly changes its mind.
        Set<UUID> handled = new HashSet<>();
        for (AutoEnrolment decided : ledger.findBySubmissionIdAndUserId(
                event.submissionId(), event.founderId())) {
            handled.add(decided.getCourseId());
        }

        Set<UUID> candidateIds = candidates.stream()
                .map(Candidate::courseId).collect(Collectors.toSet());
        Set<UUID> published = courseCatalog.findPublishedIds(candidateIds);

        // Spec §3: "AI rules silently skip courses the org can't see." SILENTLY is
        // the operative word — an invisible course writes no ledger row and no
        // log line the founder could ever see, because the alternative is telling
        // an org about a course it may not have. Batched: one query, not one per
        // candidate.
        Set<UUID> visible = courseVisibility.filterVisibleForUser(event.founderId(), candidateIds);

        // What the founder already HOLDS, for the SUGGEST branch. AUTO_ASSIGN
        // learns this for free from enrolIfAbsent's conflict; a suggestion writes
        // no enrollment, so without its own read it would offer a course the
        // founder already has — an open SUGGESTED row nothing can ever close,
        // because the effective-courses merge only surfaces SUGGESTED when there
        // is NO live enrolment, so accept() could never stamp it. One batched
        // query, and none at all when no SUGGEST rule is in play.
        Set<UUID> held = courseCatalog.findEnrolledByFounder(event.founderId(),
                candidates.stream().filter(c -> c.mode() == PillarCourseMode.SUGGEST)
                        .map(Candidate::courseId).collect(Collectors.toSet()))
                .keySet();

        for (Candidate candidate : candidates) {
            if (!visible.contains(candidate.courseId())) {
                log.debug("Auto-enrolment skipped course {}: not visible to founder {}'s organization",
                        candidate.courseId(), event.founderId());
                continue;
            }
            // An admin removed this founder from this course. Nothing happens for
            // it — no enrolment, and no ledger row, because a row would have to
            // carry an outcome the V151 CHECK does not admit. The
            // enrolment_overrides row IS the record; see the class doc.
            if (overridden.contains(candidate.courseId())) {
                log.info("Auto-enrolment skipped course {} for founder {}: an admin removed them "
                        + "from it (pillar '{}', submission {})",
                        candidate.courseId(), event.founderId(), candidate.pillarName(),
                        event.submissionId());
                continue;
            }
            // add() is false when this evaluation already decided this course —
            // either on an earlier run, or a moment ago because a second pillar
            // maps to it too. Both mean "leave it alone".
            if (!handled.add(candidate.courseId())) {
                log.debug("Auto-enrolment skip: course {} already decided for submission {}",
                        candidate.courseId(), event.submissionId());
                continue;
            }
            try {
                record(event, candidate, published.contains(candidate.courseId()),
                        held.contains(candidate.courseId()));
            } catch (DataIntegrityViolationException e) {
                // The documented race: another worker decided this course between our
                // ledger read and our write, and uq_auto_enrolments settled it. Their
                // row is the decision. Expected, benign, still counted.
                meterRegistry.counter(DECISION_FAILED, "kind", "conflict").increment();
                log.warn("Auto-enrolment course {} for founder {} was decided concurrently "
                        + "(pillar '{}', submission {}): {}",
                        candidate.courseId(), event.founderId(), candidate.pillarName(),
                        event.submissionId(), e.getMessage());
            } catch (RuntimeException e) {
                // Anything else is a genuine loss: this course is permanently unwritten
                // for this founder. One rule, one course — the rest of the journey still
                // lands, which is the point of catching inside the loop rather than
                // outside it.
                meterRegistry.counter(DECISION_FAILED, "kind", "error").increment();
                log.error("Auto-enrolment lost course {} for founder {} (pillar '{}', submission {}); "
                        + "it will not be retried: {}",
                        candidate.courseId(), event.founderId(), candidate.pillarName(),
                        event.submissionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Stored label -> band position -> that band's rules, for every pillar of the
     * evaluation.
     *
     * <p>Pillars are walked in the order the founder answered them
     * ({@code displayOrder}, id as the tie-break) so the walk is repeatable. That
     * matters for one case: the idempotency key is per (founder, course,
     * evaluation), so if TWO pillars map to the SAME course, only the first can be
     * the recorded reason. Repeatable order makes "the first" mean the earlier
     * pillar rather than whatever the database returned that day.
     *
     * <p>ponytail: one mapping query per pillar (~11 per evaluation, on the
     * {@code (pillar_id, band_position)} index) rather than one batched IN over
     * (pillar, band) pairs. Batch it if an evaluation ever carries enough pillars
     * for it to show up in a profile.
     */
    private List<Candidate> resolveCandidates(Map<UUID, String> labels) {
        List<Pillar> pillars = new ArrayList<>(pillarRepository.findAllById(labels.keySet()));
        pillars.sort(Comparator.comparingInt(Pillar::getDisplayOrder).thenComparing(Pillar::getId));

        List<Candidate> candidates = new ArrayList<>();
        for (Pillar pillar : pillars) {
            String storedLabel = labels.get(pillar.getId());
            int bandPosition = PillarBands.positionOf(pillar, storedLabel);
            if (bandPosition < 0) {
                // OFF-AXIS: the stored label names no band this pillar currently has
                // (a failed pillar's "Unknown", or a band re-labelled since). Nothing
                // happens for this pillar — never a guess at the nearest band, and
                // never a re-derivation from the score, which is the whole point of
                // reading the stored label. The competency matrix is where this
                // surfaces to a human.
                log.debug("Auto-enrolment: pillar '{}' label '{}' names no current band — skipped",
                        pillar.getName(), storedLabel);
                continue;
            }
            for (PillarCourseMapping rule :
                    mappingRepository.findByPillarIdAndBandPositionOrderByCourseIdAsc(pillar.getId(), bandPosition)) {
                candidates.add(new Candidate(pillar.getId(), pillar.getName(), bandPosition,
                        rule.getCourseId(), rule.getMode()));
            }
        }
        // Duplicates across pillars are left in and dropped by the caller's `handled`
        // set, which has to do that check anyway against the previous run's ledger.
        return candidates;
    }

    /**
     * Write the decision. The ledger row is written for every outcome, including the
     * two that enrol nobody — that row IS the answer to "why wasn't X enrolled".
     *
     * <p><strong>Two statements, no transaction around them — a CHOSEN ceiling, not
     * an unavoidable one.</strong> A crash between the enrolment write and the ledger
     * write leaves one of two states, and neither is repaired by anything:
     * <ul>
     *   <li>enrol-then-record (this order) — the founder holds the course with no
     *       recorded reason. They keep the course; {@code dashboard_recommendations}
     *       cannot say why they have it;</li>
     *   <li>record-then-enrol — the ledger asserts an enrolment that does not exist
     *       and, being the idempotency claim, blocks any later attempt at it. The
     *       founder silently loses the course and the record says otherwise.</li>
     * </ul>
     * Losing the REASON beats losing the COURSE, so the order is this one.
     *
     * <p>The window is closable: wrapping these two writes in one transaction would
     * make them atomic. It is not done because the fix is not the one-word one it
     * looks like — {@code @Transactional} on this private method is a self-invocation
     * no-op, so it needs a separate injected bean or a {@code TransactionTemplate},
     * and that transaction must stay strictly per-course or it re-creates the
     * poisoning the per-course catch exists to prevent. Deliberately deferred: the
     * window is two statements wide and its worst outcome is an understated reason on
     * a course the founder still has. Upgrade path if it ever bites: a
     * {@code TransactionTemplate} around this method body only.
     */
    private void record(SubmissionEvaluated event, Candidate candidate, boolean published,
                        boolean alreadyEnrolled) {
        AutoEnrolmentOutcome outcome;
        if (candidate.mode() == PillarCourseMode.SUGGEST && alreadyEnrolled) {
            // The founder already holds a live enrolment in this course, so there
            // is nothing to offer. Recorded in the AUTO_ASSIGN branch's
            // vocabulary — the decision keeps its ledger row and its reason, the
            // founder gets no dead "Accept" card, and the enrolment they already
            // have is left exactly as it was (NEW_ENROLMENTS_ONLY).
            outcome = AutoEnrolmentOutcome.ALREADY_ENROLLED;
            log.info("Auto-enrolment suggestion withheld: founder {} already holds course {} "
                    + "(pillar '{}', band {}, submission {})",
                    event.founderId(), candidate.courseId(), candidate.pillarName(),
                    candidate.bandPosition(), event.submissionId());
        } else if (candidate.mode() == PillarCourseMode.SUGGEST) {
            // Suggest mode (spec §3): the founder is OFFERED the course. The
            // ledger row IS the suggestion — no enrollment, no seat, no progress
            // — and one tap on the recommendation card turns it into one. The
            // published check is deliberately skipped here: the Accept path
            // re-checks state and visibility at the moment it matters, and
            // refusing to suggest a course that publishes tomorrow would lose the
            // reason for good (nothing re-drives an evaluation).
            outcome = AutoEnrolmentOutcome.SUGGESTED;
            log.info("Auto-enrolment SUGGESTED course {} to founder {} (pillar '{}', band {}, submission {})",
                    candidate.courseId(), event.founderId(), candidate.pillarName(),
                    candidate.bandPosition(), event.submissionId());
        } else if (!published) {
            // The mapping surface deliberately does not filter by state; the refusal
            // is ours. A skip, never an error: an admin mid-authoring one course must
            // not cost the founder the rest of their journey.
            outcome = AutoEnrolmentOutcome.COURSE_NOT_PUBLISHED;
            log.info("Auto-enrolment refused course {} for founder {}: not PUBLISHED (pillar '{}', band {})",
                    candidate.courseId(), event.founderId(), candidate.pillarName(), candidate.bandPosition());
        } else {
            // note: enrolIfAbsent's ON CONFLICT DO NOTHING also returns false when
            // the existing row is CANCELLED, so a founder REMOVED from this course
            // is recorded as ALREADY_ENROLLED — misleading, but the honest value
            // ("removed") is not in the outcome set, and the V151/V168 CHECK
            // reserves widening that set to a human. In practice the overridden
            // guard above catches the admin-removed case first (removals now write
            // an enrolment_overrides row); only an override-less CANCELLED row —
            // a legacy removal — still lands here.
            outcome = founderEnrolments.enrolIfAbsent(event.founderId(), candidate.courseId())
                    ? AutoEnrolmentOutcome.ENROLLED
                    : AutoEnrolmentOutcome.ALREADY_ENROLLED;
            log.info("Auto-enrolment {} course {} for founder {} (pillar '{}', band {}, submission {})",
                    outcome, candidate.courseId(), event.founderId(), candidate.pillarName(),
                    candidate.bandPosition(), event.submissionId());
        }

        AutoEnrolment row = new AutoEnrolment();
        row.setUserId(event.founderId());
        row.setCourseId(candidate.courseId());
        row.setSubmissionId(event.submissionId());
        row.setPillarId(candidate.pillarId());
        row.setBandPosition(candidate.bandPosition());
        row.setOutcome(outcome);
        // saveAndFlush, not save: uq_auto_enrolments is the backstop for two workers
        // racing the same submission, and a deferred INSERT would raise that violation
        // at COMMIT — past the caller's per-course catch, taking every other course of
        // this evaluation down with it. Flushing here puts the failure where the guard
        // is. Same reason EnrollmentService#createEnrollment flushes.
        ledger.saveAndFlush(row);
    }
}
