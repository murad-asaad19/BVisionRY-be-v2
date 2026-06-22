package com.bvisionry.evaluation;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.PromptTemplateService;
import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.enums.SubmissionFailureKind;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.evaluation.EvaluationEngine.PillarResult;
import com.bvisionry.evaluation.EvaluationEngine.PipelineEvaluationResult;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.pipeline.dto.PostCompletionLinkDto;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import com.bvisionry.reporting.service.CacheInvalidationService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

    /**
     * A claim that hasn't been refreshed within this window is treated as abandoned
     * (the worker that took it crashed mid-evaluation), so the submission can be
     * reclaimed and retried. A live worker keeps its claim fresh via
     * {@link #EVALUATION_CLAIM_HEARTBEAT_INTERVAL}, so this only needs to be a
     * comfortable multiple of that interval — not an estimate of the slowest
     * possible evaluation.
     */
    private static final Duration EVALUATION_CLAIM_STALE_AFTER = Duration.ofMinutes(10);

    /**
     * How often a worker refreshes the claim of the submission it is actively
     * evaluating, so a legitimately long-running evaluation is never mistaken for a
     * crashed one and reclaimed concurrently. Must be well under
     * {@link #EVALUATION_CLAIM_STALE_AFTER} (here 5×) to tolerate missed beats.
     */
    private static final Duration EVALUATION_CLAIM_HEARTBEAT_INTERVAL = Duration.ofMinutes(2);

    /**
     * Single daemon thread that fires the claim heartbeats for all in-flight
     * evaluations. Each beat is a tiny indexed UPDATE, so one thread comfortably
     * serves many concurrent evaluations.
     */
    private final ScheduledExecutorService claimHeartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eval-claim-heartbeat");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdownClaimHeartbeat() {
        claimHeartbeatScheduler.shutdownNow();
    }

    private final SubmissionRepository submissionRepository;
    private final PipelineRepository pipelineRepository;
    private final AnswerRepository answerRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final EvaluationEngine evaluationEngine;
    private final AIConfigService aiConfigService;
    private final PromptTemplateService promptTemplateService;
    private final EmailService emailService;
    private final PostCompletionLinkResolver postCompletionLinkResolver;
    private final MeterRegistry meterRegistry;
    private final AuditService auditService;
    private final PillarReeditService pillarReeditService;
    private final FrontendUrls frontendUrls;
    private final CacheInvalidationService cacheInvalidationService;

    /**
     * Self-reference injected lazily so intra-class calls go through the Spring
     * proxy — required for {@link #evaluateSubmissionAsync} to actually run on
     * the {@code evaluationExecutor} thread when invoked from
     * {@link #retryFailedSubmission}. Without this, @Async is bypassed and the
     * evaluation runs on the caller's (HTTP) thread.
     */
    @Autowired @Lazy
    private EvaluationService self;

    @Async("evaluationExecutor")
    public void evaluateSubmissionAsync(UUID submissionId) {
        log.info("evaluateSubmissionAsync STARTED for submission {} on thread {}", submissionId, Thread.currentThread().getName());
        try {
            evaluateSubmission(submissionId);
        } catch (Exception e) {
            log.error("Evaluation failed for submission {}: {}", submissionId, e.getMessage(), e);
            markAsFailed(submissionId, e.getMessage());
        }
    }

    @Transactional
    public void markAsFailed(UUID submissionId, String reason) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.setStatus(SubmissionStatus.FAILED);
            submission.setFailureReason(reason);
            // An unhandled evaluation failure is a SYSTEM failure — answers are valid,
            // so a retake re-runs evaluation rather than asking the respondent to re-answer.
            submission.setFailureKind(SubmissionFailureKind.SYSTEM);
            // A failed submission is no longer being evaluated — release the claim so a
            // retake can re-acquire it immediately, not only after the stale-claim window
            // ({@link #EVALUATION_CLAIM_STALE_AFTER}).
            submission.setEvaluationClaimedAt(null);
            submissionRepository.save(submission);
            log.info("Submission {} marked as FAILED", submissionId);
        });
    }

    /**
     * Re-queues a previously-failed submission for async evaluation. Resets status
     * from FAILED → SUBMITTED so {@link #evaluateSubmissionAsync} can pick it up.
     */
    @Transactional
    public void retryFailedSubmission(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));
        if (submission.getStatus() != SubmissionStatus.FAILED
                && submission.getStatus() != SubmissionStatus.NEEDS_REVIEW) {
            throw new IllegalStateException(
                    "Only FAILED or NEEDS_REVIEW submissions can be retried (was "
                            + submission.getStatus() + ")");
        }
        // Flip FAILED/NEEDS_REVIEW → SUBMITTED and clear the prior failure + claim
        // state so the re-queued evaluation can be claimed immediately.
        submission.queueForEvaluation();
        submissionRepository.save(submission);
        // Defer the async dispatch until after this transaction commits — otherwise the
        // worker reloads the submission on its own thread before the FAILED→SUBMITTED
        // write is visible and bails out of evaluateSubmission's status guard.
        // Self-reference keeps the @Async("evaluationExecutor") proxy in play.
        AfterCommit.dispatch(() -> self.evaluateSubmissionAsync(submissionId));
    }

    /**
     * Orchestrates an evaluation: claim → load → run the (slow) AI → persist the
     * outcome atomically → fire post-commit side effects. Deliberately NOT
     * {@code @Transactional} — the AI call can run for minutes and must not hold a
     * DB connection/transaction open. Each durable step takes its own short
     * transaction: the claim ({@link SubmissionRepository#claimForEvaluation}) and
     * {@link #persistEvaluationOutcome}, which snapshots the prior results to history
     * and writes the new ones together. Runs on the async executor via
     * self-invocation, so there is no open session — relations are eager-loaded,
     * never lazily.
     */
    public void evaluateSubmission(UUID submissionId) {
        // Atomically claim this submission before doing any work, so a concurrent
        // dispatch (e.g. a double-submit) can't run the AI evaluation twice and
        // let the loser overwrite the winner's result. 0 rows updated = another
        // worker already holds a fresh claim (or it's no longer SUBMITTED) — skip.
        if (submissionRepository.claimForEvaluation(
                submissionId, EVALUATION_CLAIM_STALE_AFTER.toSeconds()) == 0) {
            log.info("Submission {} is already being evaluated or no longer SUBMITTED — "
                    + "skipping duplicate evaluation", submissionId);
            return;
        }

        Submission submission = submissionRepository.findByIdWithAllRelations(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));

        // Defense-in-depth: the claim above already enforces status = SUBMITTED, so
        // this never trips in production — kept to make the contract explicit.
        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            log.warn("Submission {} is not in SUBMITTED status ({}), skipping evaluation",
                    submissionId, submission.getStatus());
            return;
        }

        // Public (anonymous) submissions have no assignment/user — the pipeline
        // comes from the public link, and with no org there is no tier, so they
        // are always evaluated with the premium prompt set.
        Assignment assignment = submission.getAssignment();
        UUID pipelineId = assignment != null
                ? assignment.getPipeline().getId()
                : submission.getPublicLink().getPipeline().getId();
        // This runs on the async executor via self-invocation, so there is no open
        // session — the pipeline must be loaded with its pillars already
        // initialized, never lazily.
        Pipeline pipeline = pipelineRepository.findByIdWithPillars(pipelineId)
                .orElseThrow(() -> new IllegalStateException("Pipeline not found: " + pipelineId));
        List<Answer> answers = answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId);
        SubscriptionTier tier = assignment != null
                ? assignment.getOrganization().getSubscriptionTier()
                : SubscriptionTier.PREMIUM;

        // Generation is tier-agnostic: every submission gets the full premium summary so
        // an upgrade reveals the stored detail with no re-evaluation. Free/premium is a
        // read-time display scope (MemberResultsService.applyViewerScope). `tier` is kept
        // only for the completion log below.
        String summaryPrompt = resolveSummaryPrompt(pipeline);

        // Public (anonymous, QR-link) submissions are evaluated with their own
        // system prompt (PUBLIC_ASSESSMENT_SYSTEM_PROMPT) and may use a dedicated
        // evaluation model — null model = inherit the default (members always use
        // the default model and the shared internal SYSTEM_PROMPT).
        boolean publicAssessment = assignment == null;
        String modelOverride = publicAssessment
                ? aiConfigService.getConfigEntity().getPublicAssessmentModel()
                : null;

        // Presence of unlock rows = the member just re-submitted a partial
        // re-edit. Snapshot the existing pillar_evaluations + overall_summary
        // to history, re-evaluate only the unlocked pillars, regenerate the
        // summary using the full pillar set, then clear the unlock rows.
        List<UUID> unlockedPillarIds = pillarReeditService.findUnlockedPillarIds(submissionId);
        boolean isPartialReeval = !unlockedPillarIds.isEmpty();

        // The AI evaluation can legitimately run for minutes; keep the claim fresh
        // on a heartbeat for its whole duration so a slow-but-alive run is never
        // mistaken for a crash and reclaimed concurrently by another worker.
        ScheduledFuture<?> heartbeat = startClaimHeartbeat(submissionId);
        PipelineEvaluationResult result;
        try {
            if (isPartialReeval) {
                // The unlocked pillars' live rows are left in place; the summary just
                // needs the pillars we are NOT re-evaluating so the engine can stitch
                // them in. Snapshotting + overwriting the unlocked rows happens
                // atomically later in persistEvaluationOutcome, so an AI run that fails
                // here loses nothing — the prior results are still live.
                Set<UUID> unlockedSet = Set.copyOf(unlockedPillarIds);
                List<PillarEvaluation> preserved = pillarEvaluationRepository.findBySubmissionId(submissionId)
                        .stream()
                        .filter(pe -> pe.getPillar() != null && !unlockedSet.contains(pe.getPillar().getId()))
                        .toList();
                result = evaluationEngine.evaluatePartialPipeline(
                        pipeline, submissionId, answers, unlockedSet,
                        preserved, summaryPrompt, modelOverride, publicAssessment);
            } else {
                result = evaluationEngine.evaluatePipeline(
                        pipeline, submissionId, answers, summaryPrompt, modelOverride, publicAssessment);
            }
        } finally {
            heartbeat.cancel(false);
        }

        // Persist the pillar rows, the overall summary, the unlock-clear and the
        // final status as ONE transaction (through the proxy so @Transactional
        // applies on the async thread). A crash can no longer leave result rows
        // committed while the submission is stranded SUBMITTED with a stale claim.
        boolean degraded = self.persistEvaluationOutcome(submissionId, pipeline, result, isPartialReeval,
                isPartialReeval ? unlockedPillarIds : null);

        // The evaluation result is now committed. Everything below is best-effort
        // post-completion bookkeeping (audit trail + notification emails) — guard it
        // so a failure can't propagate to evaluateSubmissionAsync's catch and flip an
        // already-persisted submission to FAILED, discarding a result the AI produced.
        try {
            recordEvaluationSideEffects(submission, assignment, pipeline, answers,
                    submissionId, isPartialReeval, degraded);
        } catch (RuntimeException e) {
            log.warn("Post-evaluation side effects failed for submission {} "
                    + "(results already saved): {}", submissionId, e.getMessage(), e);
        }

        log.info("Evaluation complete for submission {} (tier={}, partial={})",
                submissionId, tier, isPartialReeval);
    }

    /**
     * Best-effort post-completion bookkeeping run AFTER the evaluation outcome has
     * been committed: the org Activity-feed audit entry and the member notification
     * emails. Kept separate from {@link #evaluateSubmission} and invoked under a
     * guard so a failure here can never flip an already-persisted
     * EVALUATED/NEEDS_REVIEW submission to FAILED.
     */
    private void recordEvaluationSideEffects(Submission submission, Assignment assignment,
                                             Pipeline pipeline, List<Answer> answers,
                                             UUID submissionId, boolean isPartialReeval,
                                             boolean degraded) {
        // Attribute the evaluation to the submitting member so the entry shows
        // up in the org Activity feed (which scopes by actorId IN org members).
        // Use a distinct action for partial re-evaluations so the activity feed
        // can label them differently — the underlying entity is the same submission.
        // Public (anonymous) submissions have no actor/org to attribute, so the
        // audit entry is skipped entirely.
        if (assignment != null && submission.getUser() != null) {
            String auditAction = isPartialReeval
                    ? OrgAuditActions.ASSESSMENT_REEVALUATED
                    : OrgAuditActions.ASSESSMENT_EVALUATED;
            auditService.log(submission.getUser().getId(),
                    assignment.getOrganization().getId(),
                    auditAction,
                    OrgAuditActions.ENTITY_SUBMISSION, submissionId,
                    Map.of("pipelineName", pipeline.getName()));
        }

        // Partial re-evals skip the completion emails. The post-completion
        // CTA (external redirect / linked survey) is a one-time first-eval
        // experience — replaying it on every admin-triggered re-edit would be
        // confusing, and the user is actively engaged with the page anyway.
        // Public (anonymous) submissions have no account email — they send no
        // emails at all; respondents see results on the public results page.
        if (!degraded && !isPartialReeval && submission.getUser() != null) {
            // Send notification email. EXTERNAL post-completion redirects continue
            // to ride along inside RESULTS_READY as an inline CTA. SURVEY pairings
            // get their own dedicated POST_ASSESSMENT_SURVEY_INVITE email so the
            // survey copy is admin-editable as a first-class template, and the
            // link points at the authenticated submission-scoped survey page.
            String memberEmail = submission.getUser().getEmail();
            String memberName = resolveMemberDisplayName(submission, answers);
            String resultsUrl = frontendUrls.path("/my/assessments/" + submissionId + "/results");

            PostCompletionLinkDto postCompletion = postCompletionLinkResolver
                    .resolveForCompletionEmail(pipeline, submissionId)
                    .orElse(null);
            sendCompletionEmails(pipeline, submissionId, memberEmail, memberName, resultsUrl, postCompletion);
        }
    }

    /**
     * Persist the evaluation outcome atomically: the pillar rows, the overall
     * summary, the unlock-clear (successful partial re-eval only) and the final
     * submission status all commit together. Invoked through the Spring proxy
     * ({@code self.persistEvaluationOutcome(...)}) so {@code @Transactional}
     * applies even though the surrounding {@link #evaluateSubmission} runs on the
     * async executor with no transaction.
     *
     * @return {@code true} when the run was degraded (→ NEEDS_REVIEW), so the
     *         caller can gate the post-commit completion emails.
     */
    @Transactional
    public boolean persistEvaluationOutcome(UUID submissionId, Pipeline pipeline,
                                            PipelineEvaluationResult result, boolean isPartialReeval,
                                            List<UUID> reevaluatedPillarIds) {
        // Reload inside this transaction so we mutate a managed entity rather than
        // merging the instance loaded before the (untransacted) AI call: a stale
        // @Version on that detached copy would make the final save fail with
        // OptimisticLockException and discard a result the AI actually produced.
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));

        // Load the live rows once and reuse them for both the history snapshot and the
        // in-place upsert below, so a re-eval no longer re-queries the same rows two or
        // three times.
        List<PillarEvaluation> existingEvals = pillarEvaluationRepository.findBySubmissionId(submissionId);
        OverallSummary existingSummary = overallSummaryRepository.findBySubmissionId(submissionId).orElse(null);

        // Snapshot the rows this run is about to overwrite — in THIS transaction, so the
        // snapshot and the overwrite commit (or roll back) together. A partial re-eval
        // snapshots only the re-evaluated pillars; a full re-eval snapshots them all. The
        // live rows are left in place for the upsert, so an AI run that crashed before
        // reaching this point never loses the prior results. No-op on a first evaluation.
        Set<UUID> archiveScope = isPartialReeval ? Set.copyOf(reevaluatedPillarIds) : null;
        String archiveReason = isPartialReeval
                ? PillarReeditService.ARCHIVE_REASON_PILLAR_REEVAL
                : PillarReeditService.ARCHIVE_REASON_FULL_REEVAL;
        pillarReeditService.archiveEvaluation(submissionId, existingEvals, existingSummary,
                archiveScope, archiveReason);

        savePillarEvaluations(submission, pipeline, result.pillarResults(), existingEvals);
        saveOverallSummary(submission, result.summary(), existingSummary);

        // Fail loud: if any pillar or the overall summary could not be evaluated
        // even after the engine's repair retries, mark NEEDS_REVIEW rather than
        // EVALUATED — a degraded run must never masquerade as a clean one. Partial
        // results are still saved and visible; an admin can retry.
        long failedPillars = result.pillarResults().stream().filter(PillarResult::failed).count();
        boolean summaryFailed = result.summary().failed();
        boolean degraded = failedPillars > 0 || summaryFailed;

        // Clearing the unlock rows is the LAST step of a *successful* partial
        // re-eval. A degraded run must leave them intact so the admin can retry the
        // re-edit — triggerReevaluation requires the unlock rows to still exist.
        if (isPartialReeval && !degraded) {
            pillarReeditService.clearUnlocks(submission.getId());
        }

        submission.setEvaluatedAt(Instant.now());
        if (degraded) {
            submission.setStatus(SubmissionStatus.NEEDS_REVIEW);
            submission.setFailureReason(
                    buildDegradedReason(failedPillars, summaryFailed, result.pillarResults().size()));
            // A degraded run is a SYSTEM/AI failure (the answers are valid), so a respondent
            // retake re-runs the evaluation. Drop the claim so the retake can re-acquire it.
            submission.setFailureKind(SubmissionFailureKind.SYSTEM);
            submission.setEvaluationClaimedAt(null);
            meterRegistry.counter("bvisionry.ai.evaluation_degraded",
                    "partial", String.valueOf(isPartialReeval)).increment();
            log.warn("Submission {} evaluated with gaps → NEEDS_REVIEW ({}/{} pillars failed, summaryFailed={})",
                    submission.getId(), failedPillars, result.pillarResults().size(), summaryFailed);
        } else {
            submission.setStatus(SubmissionStatus.EVALUATED);
            submission.setFailureReason(null);
            submission.setFailureKind(null);
            // The evaluation has finished — release the claim so evaluation_claimed_at
            // never lingers as a false "in-flight" marker on a completed submission.
            submission.setEvaluationClaimedAt(null);
        }
        submissionRepository.save(submission);

        // Evict only after the transaction commits — otherwise a concurrent
        // reader can re-populate the cache mid-transaction (reading the
        // pre-evaluation state) and we end up with a stale entry that sticks
        // until the next write.
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);
        return degraded;
    }

    /**
     * Start (and return) a periodic claim refresh for an in-flight evaluation; the
     * caller MUST cancel the returned future once the evaluation finishes. A failed
     * beat is logged and ignored — the worst case is the claim going stale, which
     * the stale-window reclaim already handles.
     */
    private ScheduledFuture<?> startClaimHeartbeat(UUID submissionId) {
        long period = EVALUATION_CLAIM_HEARTBEAT_INTERVAL.toSeconds();
        return claimHeartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                submissionRepository.refreshEvaluationClaim(submissionId);
            } catch (RuntimeException e) {
                log.warn("Failed to refresh evaluation claim heartbeat for {}: {}",
                        submissionId, e.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
    }

    /**
     * Send the post-evaluation notification emails. EXTERNAL post-completion
     * URLs ride along inside RESULTS_READY as an inline CTA. SURVEY pairings
     * always send a clean RESULTS_READY (no inline external CTA) plus a
     * dedicated POST_ASSESSMENT_SURVEY_INVITE so the survey copy is admin-
     * editable as a first-class template and the link points at the
     * authenticated submission-scoped survey page.
     *
     * <p>Each send is isolated — a failure on one branch must not prevent the
     * other from going out, and neither should fail the surrounding evaluation.
     */
    private void sendCompletionEmails(Pipeline pipeline, UUID submissionId,
                                      String memberEmail, String memberName, String resultsUrl,
                                      PostCompletionLinkDto postCompletion) {
        String extUrl   = postCompletion instanceof PostCompletionLinkDto.External e ? e.url()   : null;
        String extLabel = postCompletion instanceof PostCompletionLinkDto.External e ? e.label() : null;

        sendOrLog("results-ready", submissionId, () -> emailService.sendResultsReady(
                memberEmail, memberName, pipeline.getName(), resultsUrl, extUrl, extLabel));

        if (postCompletion instanceof PostCompletionLinkDto.Survey survey) {
            String surveyUrl = frontendUrls.path(survey.url());
            sendOrLog("survey-invite", submissionId, () -> emailService.sendPostAssessmentSurveyInvite(
                    memberEmail, memberName, pipeline.getName(), survey.surveyName(),
                    surveyUrl, resultsUrl));
        }
    }

    /**
     * Run a single email send with isolated failure handling. We narrow to
     * RuntimeException (the email layer doesn't declare checked exceptions)
     * and emit a counter so a sustained outage shows up on the
     * {@code bvisionry.email.failed} metric instead of being silent in logs.
     */
    private void sendOrLog(String emailKind, UUID submissionId, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.warn("Failed to send {} email for submission {}: {}",
                    emailKind, submissionId, e.getMessage());
            meterRegistry.counter("bvisionry.email.failed", "kind", emailKind).increment();
        }
    }

    /**
     * Resolve the summary guidance prompt for this run. Per-pipeline prompts act as
     * overrides; when blank we fall back to the global default configured on the
     * AI Config page (prompt_templates table), keeping defaults centrally editable.
     */
    private String resolveSummaryPrompt(Pipeline pipeline) {
        String pipelineLevel = pipeline.getOverallSummaryPrompt();
        if (pipelineLevel != null && !pipelineLevel.isBlank()) {
            return pipelineLevel;
        }
        return promptTemplateService.getActivePromptContent(PromptType.OVERALL_SUMMARY);
    }

    /** Human-readable reason persisted on a NEEDS_REVIEW submission. */
    private static String buildDegradedReason(long failedPillars, boolean summaryFailed, int totalPillars) {
        StringBuilder sb = new StringBuilder("AI evaluation completed with gaps: ");
        if (failedPillars > 0) {
            sb.append(failedPillars).append(" of ").append(totalPillars)
              .append(" pillar(s) could not be evaluated");
        }
        if (summaryFailed) {
            if (failedPillars > 0) sb.append("; ");
            sb.append("the overall summary could not be generated");
        }
        sb.append(". Review the results and retry.");
        return sb.toString();
    }

    /**
     * Prefer the first name the member entered in the personal pillar over their
     * account display name. Two reasons: accounts often carry a handle like
     * "Test Member" that isn't how the person expects to be addressed, and the
     * rest of the results page already uses the personal-pillar name — the
     * email should match.
     */
    private String resolveMemberDisplayName(Submission submission, List<Answer> answers) {
        for (Answer a : answers) {
            Question q = a.getQuestion();
            if (q == null || !SystemQuestion.FIRST_NAME.equals(q.getSystemKey())) continue;
            String text = a.getResponseText();
            if (text != null && !text.isBlank()) return text.trim();
        }
        if (submission.getUser() != null) {
            return submission.getUser().getName();
        }
        // Public (anonymous) submissions have no account — fall back to the
        // respondent name captured at session start, else a neutral label.
        String respondentName = submission.getRespondentName();
        return respondentName != null && !respondentName.isBlank()
                ? respondentName.trim()
                : "Participant";
    }

    private void savePillarEvaluations(Submission submission, Pipeline pipeline,
                                        List<PillarResult> pillarResults,
                                        List<PillarEvaluation> existingEvals) {
        String fallbackModel = aiConfigService.getConfigEntity().getDefaultEvaluationModel();
        Map<UUID, Pillar> pillarsById = pipeline.getPillars().stream()
                .collect(Collectors.toMap(Pillar::getId, p -> p));
        // Re-use any existing row per pillar so a re-evaluation (retry / admin
        // re-eval) updates in place instead of inserting a duplicate. The rows were
        // loaded once by the caller and reused for the history snapshot above.
        Map<UUID, PillarEvaluation> existingByPillar = existingEvals.stream()
                .filter(e -> e.getPillar() != null)
                .collect(Collectors.toMap(e -> e.getPillar().getId(), e -> e, (a, b) -> a));
        List<PillarEvaluation> evaluations = new ArrayList<>();
        for (PillarResult pr : pillarResults) {
            PillarEvaluation evaluation = existingByPillar.getOrDefault(pr.pillarId(), new PillarEvaluation());
            evaluation.setSubmission(submission);
            evaluation.setPillar(pillarsById.get(pr.pillarId()));
            evaluation.setScorePercentage(pr.scorePercentage());
            evaluation.setMaturityLabel(pr.maturityLabel());
            evaluation.setAiRawResponse(pr.rawResponse());
            evaluation.setEvaluatedAt(Instant.now());

            // Fall back to the global default model so provenance columns are never null.
            if (pr.provenance() != null) {
                evaluation.setAiModelUsed(pr.provenance().model());
                evaluation.setAiTemperature(pr.provenance().temperature());
                evaluation.setAiSystemPromptVersionId(pr.provenance().systemPromptVersionId());
            } else {
                evaluation.setAiModelUsed(fallbackModel);
            }
            evaluation.setAiRubricSnapshot(pr.rubricSnapshot());
            evaluation.setAiFailed(pr.failed());

            if (pr.aiResult() != null) {
                evaluation.setAiScoreMeans(pr.aiResult().whatThisScoreMeans());
                evaluation.setAiWhatsWorking(pr.aiResult().whatsWorking());
                evaluation.setAiWhatCanImprove(pr.aiResult().whatCanImprove());
                evaluation.setAiBusinessRelevance(pr.aiResult().whyThisMattersForBusiness());
                evaluation.setAiEvidence(pr.aiResult().evidence());
            }

            if (pr.selfAssessmentGap() != null) {
                evaluation.setSelfAssessmentGap(pr.selfAssessmentGap());
            }

            evaluations.add(evaluation);
        }
        pillarEvaluationRepository.saveAll(evaluations);
    }

    private void saveOverallSummary(Submission submission, EvaluationEngine.SummaryResult sr,
                                    OverallSummary existing) {
        String fallbackModel = aiConfigService.getConfigEntity().getDefaultEvaluationModel();
        // Re-use the existing summary row so a re-evaluation updates in place
        // instead of colliding with the unique (submission_id) constraint — the
        // bug that previously turned a re-run into a hard "Evaluation Failed". The
        // row was loaded once by the caller and reused for the history snapshot.
        OverallSummary summary = existing != null ? existing : new OverallSummary();
        summary.setSubmission(submission);
        summary.setGeneratedAt(Instant.now());
        summary.setOverallScorePercentage(sr.overallScore());
        summary.setSummaryNarrative(sr.summaryNarrative());
        summary.setStrengths(sr.strengths());
        summary.setDevelopmentAreas(sr.developmentAreas());
        summary.setCorePattern(sr.corePattern());
        summary.setMovingForwardNarrative(sr.movingForwardNarrative());
        summary.setAiSummaryPromptSnapshot(sr.summaryPromptSnapshot());

        if (sr.provenance() != null) {
            summary.setAiModelUsed(sr.provenance().model());
            summary.setAiTemperature(sr.provenance().temperature());
            summary.setAiSystemPromptVersionId(sr.provenance().systemPromptVersionId());
        } else {
            summary.setAiModelUsed(fallbackModel);
        }

        overallSummaryRepository.save(summary);
    }
}
