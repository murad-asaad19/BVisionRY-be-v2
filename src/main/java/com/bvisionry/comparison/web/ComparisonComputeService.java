package com.bvisionry.comparison.web;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.event.EvaluationEvents;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.dto.RecomputeResponse;
import com.bvisionry.comparison.repository.ComparisonPillarMappingRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.PairCohortRow;
import com.bvisionry.comparison.repository.ComparisonReadRepository.PillarEvalRow;
import com.bvisionry.comparison.repository.ComparisonReadRepository.SubmissionRow;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the stored comparison object (spec §5): once, when the distance
 * submission is evaluated — triggered by the {@code evaluation} slice's
 * committed-completion event — and again only through the explicit, logged
 * SUPER_ADMIN "Recompute cohort" action.
 *
 * <p>Maturity labels are read from the STORED {@code pillar_evaluations} rows
 * — the label of record that {@code ScoringService.deriveMaturityLabel}
 * produced at evaluation time (RULING 4 / D-2: never re-derive a band from a
 * score) — so the algorithm is reused, not duplicated. Shift bands are read
 * from the platform config and STAMPED into {@code config_snapshot}; config
 * edits apply forward only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComparisonComputeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComparisonReadRepository reads;
    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository comparisonPillars;
    private final ComparisonPillarMappingRepository mappingRepo;
    private final ComparisonMappingService mappingService;
    private final ShiftNarrativeService narratives;
    private final AuditLogger auditLogger;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * A submission finished evaluating cleanly. If its pipeline is either side
     * of a designated pair for any cohort the founder belongs to, and both
     * sides now have an evaluated submission, compute the missing comparison.
     * (Listening on both sides covers the rare baseline-evaluated-after-
     * distance ordering.) Never throws — the event's other listeners and the
     * member's results must not be disturbed by a comparison failure.
     */
    @EventListener
    public void onSubmissionEvaluated(EvaluationEvents.SubmissionEvaluated event) {
        try {
            Optional<SubmissionRow> submission = reads.submission(event.submissionId());
            if (submission.isEmpty()) {
                return;
            }
            UUID pipelineId = submission.get().pipelineId();
            for (PairCohortRow pair : reads.pairsInvolving(event.founderId(), pipelineId)) {
                if (comparisons.findByCohortIdAndUserId(pair.cohortId(), event.founderId()).isEmpty()) {
                    // TransactionTemplate, not @Transactional: the listener runs on
                    // the evaluation's async thread via `this`, where a self-invoked
                    // @Transactional would silently not apply, and the comparison +
                    // its pillar rows must commit together or not at all.
                    boolean computed = Boolean.TRUE.equals(transactionTemplate.execute(
                            status -> computeIfReady(pair, event.founderId())));
                    if (computed) {
                        // §5 "distance done" is the narrative job's trigger (§6).
                        // Dispatched AFTER the commit above, on its own executor, so
                        // the model round-trips neither extend the compute
                        // transaction nor can fail the comparison.
                        narratives.generateTopPillarsAsync(pair.cohortId(), event.founderId());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Comparison compute failed for submission {} (evaluation unaffected): {}",
                    event.submissionId(), e.getMessage(), e);
        }
    }

    /**
     * The explicit history-restating action (spec §7): recompute every member
     * of the cohort with current config + mapping. Logged.
     *
     * <p>Narratives are NOT regenerated and NOT deleted: §7 says approved
     * narratives never auto-recompute — they return to draft so a human re-reads
     * prose whose numbers have moved under it. Their anchor is
     * {@code (cohort, user, distance pillar)}, not the comparison row this
     * method deletes and rebuilds, so they simply survive the rebuild (see
     * {@code V169} and {@code ShiftNarrative}) — with their decline flag and
     * band label re-stamped from the new rows, so a changed band config binds.
     */
    @Transactional
    public RecomputeResponse recomputeCohort(UUID cohortId, UUID actorId) {
        PairCohortRow pair = reads.designatedPair(cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Designated comparison pair for cohort", cohortId.toString()));
        int revertedNarratives = narratives.revertApprovalsForCohort(cohortId);
        int recomputed = 0;
        for (UUID userId : reads.cohortMembers(cohortId)) {
            comparisons.findByCohortIdAndUserId(cohortId, userId).ifPresent(existing -> {
                comparisonPillars.deleteByComparisonId(existing.getId());
                comparisons.delete(existing);
            });
            comparisons.flush();
            if (computeIfReady(pair, userId)) {
                recomputed++;
            }
        }
        // AFTER the rebuild: re-derive each narrative's decline flag + band label
        // from the new pillar rows, so a §7 band edit binds the §6 guardrail
        // (a pillar that is a decline NOW cannot be approved without a next step).
        narratives.restampBandsForCohort(cohortId);
        auditLogger.log(actorId, null, "COMPARISON_COHORT_RECOMPUTED", "Cohort",
                cohortId, Map.of("recomputed", recomputed,
                        "narrativesReturnedToDraft", revertedNarratives));
        log.info("Recomputed {} comparisons for cohort {} (actor {}); {} approved narrative(s) "
                + "returned to draft", recomputed, cohortId, actorId, revertedNarratives);
        return new RecomputeResponse(recomputed);
    }

    /* --------------------------------------------------------- the compute */

    /**
     * Computes and stores the comparison when both sides are evaluated. Runs
     * inside the CALLER's transaction ({@link #recomputeCohort} or the
     * listener's {@code TransactionTemplate}) — deliberately not
     * {@code @Transactional} itself, since both callers reach it by
     * self-invocation.
     */
    boolean computeIfReady(PairCohortRow pair, UUID userId) {
        Optional<Sides> sides = resolveSides(pair, userId);
        if (sides.isEmpty()) {
            return false;
        }
        SubmissionRow baselineRow = sides.get().baseline();
        SubmissionRow distanceRow = sides.get().distance();

        List<ScoringBands.Band> bands = currentShiftBands();
        BigDecimal before = reads.overallScore(baselineRow.id()).orElse(null);
        BigDecimal after = reads.overallScore(distanceRow.id()).orElse(null);
        BigDecimal delta = before != null && after != null ? after.subtract(before) : null;
        ScoringBands.Band overallBand = delta == null ? null : ScoringBands.resolve(bands, delta);

        FounderComparison c = new FounderComparison();
        c.setCohortId(pair.cohortId());
        // The MEMBER's org, not a cohort org — a platform cohort spans orgs
        // (spec §13) and the comparison row is what org surfaces tenant-filter on.
        c.setOrgId(reads.userOrg(userId).orElse(null));
        c.setUserId(userId);
        c.setBaselineSubmissionId(baselineRow.id());
        c.setDistanceSubmissionId(distanceRow.id());
        c.setOverallBefore(before);
        c.setOverallAfter(after);
        c.setOverallDelta(delta);
        c.setOverallBandKey(overallBand == null ? null : overallBand.key());
        c.setOverallBandLabel(overallBand == null ? null : overallBand.label());
        c.setConfigSnapshot(bands);
        c.setComputedAt(Instant.now());
        comparisons.save(c);

        comparisonPillars.saveAll(pillarRows(c, pair, baselineRow.id(), distanceRow.id(), bands));
        return true;
    }

    /** The two submissions a comparison is built from. */
    record Sides(SubmissionRow baseline, SubmissionRow distance) {
    }

    /**
     * Resolves the pair's two sides for a founder, or empty when the
     * comparison is not computable yet. Shared by the compute and by
     * {@link #foundersAwaitingComparison} so "ready" can never mean two
     * different things on the write and the read side.
     */
    Optional<Sides> resolveSides(PairCohortRow pair, UUID userId) {
        // Typed task spine (spec §5): milestone-task submission tags beat the
        // by-pipeline heuristics — they are what make same-pipeline pairs
        // resolvable. Baseline = the submission tagged to the cohort's
        // BASELINE milestone, else EARLIEST evaluated of the baseline pipeline
        // (intake semantics; retakes never shift it). Distance = the
        // submission tagged to the DISTANCE milestone (any publish status —
        // the tag is authoritative); when the cohort has no distance milestone
        // at all, fall back to latest-evaluated ONLY for different-pipeline
        // pairs (the pre-spine behavior) — for an equal pair "latest" cannot
        // distinguish a check-in from the distance.
        Optional<SubmissionRow> baseline = reads.milestoneTask(pair.cohortId(), "BASELINE")
                .flatMap(t -> reads.latestEvaluatedTaggedSubmission(
                        userId, t.taskId(), pair.baselinePipelineId()))
                .or(() -> reads.earliestEvaluatedSubmission(userId, pair.baselinePipelineId()));

        Optional<ComparisonReadRepository.MilestoneTaskRow> distanceTask =
                reads.milestoneTask(pair.cohortId(), "DISTANCE");
        Optional<SubmissionRow> distance;
        if (distanceTask.isPresent()) {
            distance = reads.latestEvaluatedTaggedSubmission(
                    userId, distanceTask.get().taskId(), pair.distancePipelineId());
        } else if (!pair.baselinePipelineId().equals(pair.distancePipelineId())) {
            distance = reads.latestEvaluatedSubmission(userId, pair.distancePipelineId());
        } else {
            distance = Optional.empty();
        }
        if (baseline.isEmpty() || distance.isEmpty()) {
            return Optional.empty();
        }
        if (baseline.get().id().equals(distance.get().id())) {
            // Defense in depth against a self-comparison (e.g. an equal pair
            // where the member's only evaluated submission is the distance).
            return Optional.empty();
        }
        return Optional.of(new Sides(baseline.get(), distance.get()));
    }

    /* ------------------------------------------------------ the detection */

    /**
     * Enrolled founders of the cohort whose comparison IS computable — both
     * sides evaluated per {@link #resolveSides} — but for whom no
     * {@code founder_comparisons} row exists. Derived from data that already
     * exists; nothing about the failure is stored.
     *
     * <p>Empty when the cohort has no fully-designated pair: without a pair
     * there is nothing to compute and nothing to report.
     */
    @Transactional(readOnly = true)
    public List<UUID> foundersAwaitingComparison(UUID cohortId) {
        Optional<PairCohortRow> pair = reads.designatedPair(cohortId);
        if (pair.isEmpty()) {
            return List.of();
        }
        // ponytail: roster-bounded N+1 of indexed lookups, reusing the exact
        // resolution the compute uses. Fold into one SQL if a cohort ever
        // grows past the point where an admin read notices.
        return reads.cohortMembers(cohortId).stream()
                .filter(userId -> comparisons.findByCohortIdAndUserId(cohortId, userId).isEmpty())
                .filter(userId -> resolveSides(pair.get(), userId).isPresent())
                .toList();
    }

    private List<FounderComparisonPillar> pillarRows(FounderComparison c, PairCohortRow pair,
                                                     UUID baselineSubmissionId, UUID distanceSubmissionId,
                                                     List<ScoringBands.Band> bands) {
        // Ensure the pair's mapping exists (auto-seed on first use).
        mappingService.mappingForPair(pair.baselinePipelineId(), pair.distancePipelineId());

        Map<UUID, PillarEvalRow> baseEvals = reads.pillarEvaluations(baselineSubmissionId);
        Map<UUID, PillarEvalRow> distEvals = reads.pillarEvaluations(distanceSubmissionId);
        Map<UUID, String> names = new java.util.HashMap<>();
        reads.pillarsOf(pair.baselinePipelineId()).forEach(p -> names.put(p.id(), p.name()));
        reads.pillarsOf(pair.distancePipelineId()).forEach(p -> names.put(p.id(), p.name()));

        List<FounderComparisonPillar> rows = new ArrayList<>();
        for (var m : mappingRepo.findByBaselinePipelineIdAndDistancePipelineId(
                pair.baselinePipelineId(), pair.distancePipelineId())) {
            PillarEvalRow before = m.getBaselinePillarId() == null
                    ? null : baseEvals.get(m.getBaselinePillarId());
            PillarEvalRow after = m.getDistancePillarId() == null
                    ? null : distEvals.get(m.getDistancePillarId());
            if (before == null && after == null) {
                // Neither submission measured this row (e.g. pillar added after
                // both were taken) — nothing to report yet.
                continue;
            }

            FounderComparisonPillar row = new FounderComparisonPillar();
            row.setComparisonId(c.getId());
            row.setBaselinePillarId(m.getBaselinePillarId());
            row.setDistancePillarId(m.getDistancePillarId());
            String name = after != null
                    ? names.get(m.getDistancePillarId())
                    : names.get(m.getBaselinePillarId());
            row.setPillarNameSnapshot(name == null ? "Unknown pillar" : name);
            if (before != null && after != null) {
                row.setState(PillarComparisonState.MAPPED);
                row.setBeforePct(before.scorePercentage());
                row.setAfterPct(after.scorePercentage());
                BigDecimal delta = after.scorePercentage().subtract(before.scorePercentage());
                row.setDelta(delta);
                ScoringBands.Band band = ScoringBands.resolve(bands, delta);
                row.setBandKey(band == null ? null : band.key());
                row.setBandLabel(band == null ? null : band.label());
                row.setMaturityBefore(before.maturityLabel());
                row.setMaturityAfter(after.maturityLabel());
            } else if (after != null) {
                row.setState(PillarComparisonState.NEWLY_MEASURED);
                row.setAfterPct(after.scorePercentage());
                row.setMaturityAfter(after.maturityLabel());
            } else {
                row.setState(PillarComparisonState.NOT_REMEASURED);
                row.setBeforePct(before.scorePercentage());
                row.setMaturityBefore(before.maturityLabel());
            }
            rows.add(row);
        }
        return rows;
    }

    /** The shift bands to compute with: platform config, or shipped defaults. */
    List<ScoringBands.Band> currentShiftBands() {
        return reads.settingText(ScoringBands.SHIFT_BANDS_KEY)
                .flatMap(json -> {
                    try {
                        BandsDoc doc = MAPPER.readValue(json, BandsDoc.class);
                        return Optional.ofNullable(doc.bands());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        log.warn("Stored shift bands are unparseable; using defaults: {}",
                                e.getOriginalMessage());
                        return Optional.empty();
                    }
                })
                .orElseGet(ScoringBands::defaultShiftBands);
    }

    /** Mirrors the platform slice's {@code BandsDoc} envelope ({"bands":[...]}). */
    record BandsDoc(List<ScoringBands.Band> bands) {
    }
}
