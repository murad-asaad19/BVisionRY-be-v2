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
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.tx.AfterCommit;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

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

    @org.springframework.beans.factory.annotation.Value("${bvisionry.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;
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
        if (submission.getStatus() != SubmissionStatus.FAILED) {
            throw new IllegalStateException(
                    "Only FAILED submissions can be retried (was " + submission.getStatus() + ")");
        }
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setFailureReason(null);
        // Clear any stale evaluatedAt from the prior (pre-failure) success —
        // otherwise the UI timeline renders a timestamp on a pending step.
        submission.setEvaluatedAt(null);
        submissionRepository.save(submission);
        // Defer the async dispatch until after this transaction commits — otherwise the
        // worker reloads the submission on its own thread before the FAILED→SUBMITTED
        // write is visible and bails out of evaluateSubmission's status guard.
        // Self-reference keeps the @Async("evaluationExecutor") proxy in play.
        AfterCommit.run(() -> self.evaluateSubmissionAsync(submissionId));
    }

    @Transactional
    public void evaluateSubmission(UUID submissionId) {
        Submission submission = submissionRepository.findByIdWithAllRelations(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));

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
        // This runs on the async executor via self-invocation, so @Transactional
        // does not apply and there is no open session — the pipeline must be
        // loaded with its pillars already initialized, never lazily.
        Pipeline pipeline = pipelineRepository.findByIdWithPillars(pipelineId)
                .orElseThrow(() -> new IllegalStateException("Pipeline not found: " + pipelineId));
        List<Answer> answers = answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId);
        SubscriptionTier tier = assignment != null
                ? assignment.getOrganization().getSubscriptionTier()
                : SubscriptionTier.PREMIUM;

        boolean freeTier = tier == SubscriptionTier.FREE;
        String summaryPrompt = resolveSummaryPrompt(pipeline, freeTier);

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

        PipelineEvaluationResult result;
        if (isPartialReeval) {
            pillarReeditService.archiveBeforeReeval(submission, unlockedPillarIds);
            // After archiving, the rows for unlocked pillars + the overall summary
            // are gone; the rows for preserved (locked) pillars remain — load
            // them so the summary can see all pillars.
            List<PillarEvaluation> preserved = pillarEvaluationRepository.findBySubmissionId(submissionId);
            result = evaluationEngine.evaluatePartialPipeline(
                    pipeline, submissionId, answers, Set.copyOf(unlockedPillarIds),
                    preserved, summaryPrompt, freeTier, modelOverride, publicAssessment);
        } else {
            result = evaluationEngine.evaluatePipeline(
                    pipeline, submissionId, answers, summaryPrompt, freeTier, modelOverride, publicAssessment);
        }

        savePillarEvaluations(submission, pipeline, result.pillarResults());
        saveOverallSummary(submission, result.summary());

        if (isPartialReeval) {
            pillarReeditService.clearUnlocks(submissionId);
        }

        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());
        submissionRepository.save(submission);

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

        // Evict only after the transaction commits — otherwise a concurrent
        // reader can re-populate the cache mid-transaction (reading the
        // pre-evaluation state) and we end up with a stale entry that sticks
        // until the next write.
        AfterCommit.run(cacheInvalidationService::invalidateOnNewEvaluation);

        // Partial re-evals skip the completion emails. The post-completion
        // CTA (external redirect / linked survey) is a one-time first-eval
        // experience — replaying it on every admin-triggered re-edit would be
        // confusing, and the user is actively engaged with the page anyway.
        // Public (anonymous) submissions have no account email — they send no
        // emails at all; respondents see results on the public results page.
        if (!isPartialReeval && submission.getUser() != null) {
            // Send notification email. EXTERNAL post-completion redirects continue
            // to ride along inside RESULTS_READY as an inline CTA. SURVEY pairings
            // get their own dedicated POST_ASSESSMENT_SURVEY_INVITE email so the
            // survey copy is admin-editable as a first-class template, and the
            // link points at the authenticated submission-scoped survey page.
            String memberEmail = submission.getUser().getEmail();
            String memberName = resolveMemberDisplayName(submission, answers);
            String resultsUrl = frontendBaseUrl + "/my/assessments/" + submissionId + "/results";

            PostCompletionLinkDto postCompletion = postCompletionLinkResolver
                    .resolveForCompletionEmail(pipeline, submissionId)
                    .orElse(null);
            sendCompletionEmails(pipeline, submissionId, memberEmail, memberName, resultsUrl, postCompletion);
        }

        log.info("Evaluation complete for submission {} (tier={}, partial={})",
                submissionId, tier, isPartialReeval);
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
            String surveyUrl = frontendBaseUrl + survey.url();
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
    private String resolveSummaryPrompt(Pipeline pipeline, boolean freeTier) {
        String pipelineLevel = freeTier ? pipeline.getFreeTierPrompt() : pipeline.getOverallSummaryPrompt();
        if (pipelineLevel != null && !pipelineLevel.isBlank()) {
            return pipelineLevel;
        }
        PromptType type = freeTier ? PromptType.FREE_TIER_SUMMARY : PromptType.OVERALL_SUMMARY;
        return promptTemplateService.getActivePromptContent(type);
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
                                        List<PillarResult> pillarResults) {
        String fallbackModel = aiConfigService.getConfigEntity().getDefaultEvaluationModel();
        Map<UUID, Pillar> pillarsById = pipeline.getPillars().stream()
                .collect(Collectors.toMap(Pillar::getId, p -> p));
        List<PillarEvaluation> evaluations = new ArrayList<>();
        for (PillarResult pr : pillarResults) {
            PillarEvaluation evaluation = new PillarEvaluation();
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

    private void saveOverallSummary(Submission submission, EvaluationEngine.SummaryResult sr) {
        String fallbackModel = aiConfigService.getConfigEntity().getDefaultEvaluationModel();
        OverallSummary summary = new OverallSummary();
        summary.setSubmission(submission);
        summary.setGeneratedAt(Instant.now());
        summary.setOverallScorePercentage(sr.overallScore());
        summary.setSummaryNarrative(sr.summaryNarrative());
        summary.setStrengths(sr.strengths());
        summary.setDevelopmentAreas(sr.developmentAreas());
        summary.setRecommendations(sr.recommendations());
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
