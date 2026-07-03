package com.bvisionry.evaluation;

import com.bvisionry.assessment.SubmissionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recovers submissions stranded in {@code SUBMITTED} — the durability backstop
 * for the in-memory {@code evaluationExecutor}.
 *
 * <p>A submission reaches {@code SUBMITTED} in its own committed transaction and
 * is then dispatched fire-and-forget onto the executor. If that dispatch is lost
 * — executor saturation (our rejection handler drops it), an OOM, or a Railway
 * redeploy mid-evaluation — nothing else would ever re-run it: the admin retry
 * path only accepts FAILED/NEEDS_REVIEW, so the respondent's completed assessment
 * would sit in "evaluation in progress" forever. This scheduled sweep closes that
 * gap by periodically re-dispatching stranded rows.
 *
 * <p>Re-dispatch is safe and idempotent: {@code evaluateSubmissionAsync} →
 * {@link SubmissionRepository#claimForEvaluation} atomically gates on status +
 * claim freshness, so a row that is actually being worked (fresh heartbeat) or
 * already done is skipped. The reaper therefore never double-evaluates; worst
 * case it re-queues a row that another worker grabs first.
 */
@Component
@Slf4j
public class EvaluationReaper {

    private final SubmissionRepository submissionRepository;
    private final EvaluationService evaluationService;

    /** Skip rows submitted within this window — the executor is still draining them normally. */
    private final long graceSeconds;
    /** Skip rows whose claim is fresher than this — a live worker still holds them. */
    private final long staleSeconds;
    /** Max rows re-dispatched per tick; overflow self-throttles into later ticks. */
    private final int batchLimit;

    /** Last observed backlog, surfaced as a gauge for alerting (F25). */
    private final AtomicLong backlog = new AtomicLong(0);

    public EvaluationReaper(
            SubmissionRepository submissionRepository,
            EvaluationService evaluationService,
            MeterRegistry meterRegistry,
            @Value("${bvisionry.evaluation.reaper.grace-seconds:180}") long graceSeconds,
            @Value("${bvisionry.evaluation.reaper.stale-seconds:600}") long staleSeconds,
            @Value("${bvisionry.evaluation.reaper.batch-limit:50}") int batchLimit) {
        this.submissionRepository = submissionRepository;
        this.evaluationService = evaluationService;
        this.graceSeconds = graceSeconds;
        this.staleSeconds = staleSeconds;
        this.batchLimit = batchLimit;
        Gauge.builder("bvisionry.ai.evaluation_backlog", backlog, AtomicLong::get)
                .description("Submissions stuck in SUBMITTED past the grace window, awaiting (re)evaluation")
                .register(meterRegistry);
    }

    /**
     * Periodic recovery sweep. Updates the backlog gauge every tick (so a stalled
     * pipeline is visible even when nothing is re-dispatchable yet) and re-queues a
     * bounded batch of stranded rows. Fully guarded — a failure here must never kill
     * the scheduler thread.
     */
    // 90s cadence; the sweep only counts + fire-and-forget re-dispatches a bounded batch,
    // so it finishes in seconds — 2m ceiling covers a slow DB tick, 30s floor stops a
    // second replica racing the same tick on clock skew.
    @Scheduled(
            fixedDelayString = "${bvisionry.evaluation.reaper.interval-ms:90000}",
            initialDelayString = "${bvisionry.evaluation.reaper.interval-ms:90000}")
    @SchedulerLock(name = "EvaluationReaper_recoverStrandedSubmissions",
            lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    public void recoverStrandedSubmissions() {
        try {
            long stuck = submissionRepository.countStrandedSubmitted(graceSeconds);
            backlog.set(stuck);
            if (stuck == 0) {
                return;
            }

            List<UUID> ids = submissionRepository.findStrandedSubmittedIds(graceSeconds, staleSeconds, batchLimit);
            if (ids.isEmpty()) {
                return;
            }

            log.warn("EvaluationReaper: {} stranded SUBMITTED submission(s) this batch (backlog~{}); re-dispatching",
                    ids.size(), stuck);
            int dispatched = 0;
            for (UUID id : ids) {
                try {
                    evaluationService.evaluateSubmissionAsync(id);
                    dispatched++;
                } catch (RuntimeException e) {
                    // e.g. executor saturated again — leave it stranded for the next tick.
                    log.warn("EvaluationReaper: re-dispatch of submission {} failed: {}", id, e.getMessage());
                }
            }
            log.info("EvaluationReaper: re-dispatched {} of {} stranded submission(s)", dispatched, ids.size());
        } catch (RuntimeException e) {
            log.error("EvaluationReaper sweep failed: {}", e.getMessage(), e);
        }
    }
}
