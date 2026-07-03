package com.bvisionry.evaluation;

import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.PromptTemplateService;
import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.notification.EmailService;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
import com.bvisionry.reporting.service.CacheInvalidationService;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock private SubmissionRepository submissionRepository;
    @Mock private com.bvisionry.pipeline.repository.PipelineRepository pipelineRepository;
    @Mock private AnswerRepository answerRepository;
    @Mock private PillarEvaluationRepository pillarEvaluationRepository;
    @Mock private OverallSummaryRepository overallSummaryRepository;
    @Mock private EvaluationEngine evaluationEngine;
    @Mock private AIConfigService aiConfigService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private EmailService emailService;
    @Mock private CacheInvalidationService cacheInvalidationService;
    @Mock private PostCompletionLinkResolver postCompletionLinkResolver;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private MeterRegistry meterRegistry;
    @Mock private com.bvisionry.audit.AuditService auditService;
    @Mock private PillarReeditService pillarReeditService;
    @Mock private RateLimitService rateLimitService;

    @InjectMocks
    private EvaluationService evaluationService;

    private UUID submissionId;
    private Submission submission;
    private Pipeline pipeline;
    private Pillar pillar;
    private Answer freeTextAnswer;
    private Answer likertAnswer;
    private AIConfiguration aiConfiguration;

    @BeforeEach
    void setUp() {
        // EvaluationService builds the results-ready link via FrontendUrls; inject a
        // real one so the email-send path doesn't NPE (URL value isn't asserted).
        ReflectionTestUtils.setField(evaluationService, "frontendUrls",
                new FrontendUrls(new FrontendProperties()));
        // The result-persist step runs through the self proxy so its @Transactional
        // applies on the async worker thread; point it back at this same instance so
        // the unit test exercises the real persist method.
        ReflectionTestUtils.setField(evaluationService, "self", evaluationService);
        submissionId = UUID.randomUUID();

        pipeline = new Pipeline();
        pipeline.setId(UUID.randomUUID());
        pipeline.setName("Test Pipeline");

        pillar = new Pillar();
        pillar.setId(UUID.randomUUID());
        pillar.setName("Leadership");
        pillar.setType(PillarType.STANDARD);
        pillar.setWeight(new BigDecimal("1.00"));
        pillar.setAiRubricInstructions("Evaluate leadership capabilities...");
        pillar.setMaturityThresholds(Map.of(
                "Emerging", List.of(0, 59),
                "Strong", List.of(60, 79),
                "Elite", List.of(80, 100)
        ));
        pillar.setPipeline(pipeline);

        Question freeTextQuestion = new Question();
        freeTextQuestion.setId(UUID.randomUUID());
        freeTextQuestion.setType(QuestionType.FREE_TEXT);
        freeTextQuestion.setPromptText("Describe your leadership style");
        freeTextQuestion.setWeight(new BigDecimal("2.00"));
        freeTextQuestion.setPillar(pillar);

        Question likertQuestion = new Question();
        likertQuestion.setId(UUID.randomUUID());
        likertQuestion.setType(QuestionType.LIKERT);
        likertQuestion.setPromptText("I communicate effectively with my team");
        likertQuestion.setWeight(new BigDecimal("1.00"));
        likertQuestion.setPillar(pillar);

        pillar.setQuestions(List.of(freeTextQuestion, likertQuestion));
        pipeline.setPillars(List.of(pillar));

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@test.com");
        user.setName("Test User");

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setSubscriptionTier(SubscriptionTier.PREMIUM);

        Assignment assignment = new Assignment();
        assignment.setId(UUID.randomUUID());
        assignment.setPipeline(pipeline);
        assignment.setOrganization(organization);

        submission = new Submission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(user);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(Instant.now());

        freeTextAnswer = new Answer();
        freeTextAnswer.setId(UUID.randomUUID());
        freeTextAnswer.setSubmission(submission);
        freeTextAnswer.setQuestion(freeTextQuestion);
        freeTextAnswer.setResponseText("I lead by example with open communication...");

        likertAnswer = new Answer();
        likertAnswer.setId(UUID.randomUUID());
        likertAnswer.setSubmission(submission);
        likertAnswer.setQuestion(likertQuestion);
        likertAnswer.setSelectedValue("4");

        aiConfiguration = new AIConfiguration();
        aiConfiguration.setDefaultEvaluationModel("anthropic/claude-sonnet-4");

        // evaluateSubmission re-loads the pipeline with pillars initialized (the
        // async path has no session for lazy loading); lenient because the
        // guard-clause tests return before reaching the pipeline load.
        lenient().when(pipelineRepository.findByIdWithPillars(pipeline.getId()))
                .thenReturn(Optional.of(pipeline));
        // Every evaluation first atomically claims the submission; default to a
        // won claim so the eval-path tests proceed. Lenient because the skip-path
        // test overrides it and the guard tests return before reaching it.
        lenient().when(submissionRepository.claimForEvaluation(eq(submissionId), anyLong()))
                .thenReturn(1);
        // persistEvaluationOutcome reloads the submission inside its own transaction
        // (a managed entity, not the detached one from findByIdWithAllRelations).
        lenient().when(submissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission));
    }

    @AfterEach
    void tearDown() {
        // EvaluationService spins up a real daemon heartbeat scheduler in its field
        // initializer; @PreDestroy never fires under @InjectMocks, so shut it down
        // here to avoid leaking a thread per test method.
        evaluationService.shutdownClaimHeartbeat();
    }

    private EvaluationEngine.PipelineEvaluationResult buildMockResult() {
        PillarEvaluationResult aiResult = new PillarEvaluationResult(
                80,
                "Good leadership approach",
                List.of("Clear communication"),
                List.of("Delegation skills"),
                "Leadership drives team performance",
                List.of()
        );

        EvaluationEngine.PillarResult pillarResult = new EvaluationEngine.PillarResult(
                pillar.getId(), "Leadership", null,
                new BigDecimal("80.00"), "Strong",
                aiResult, "raw json", null,
                null, null, false
        );

        EvaluationEngine.SummaryResult summary = new EvaluationEngine.SummaryResult(
                new BigDecimal("78"), "Good overall performance",
                List.of("Communication strength"), List.of("Delegation"),
                "Strong communicator", "Continue developing delegation", "raw json",
                null, null, false
        );

        return new EvaluationEngine.PipelineEvaluationResult(List.of(pillarResult), summary);
    }

    @Test
    void evaluateSubmission_processesAllPillars_createsEvaluations() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // Member submissions never carry a model override and use the internal
        // (non-public) system prompt — publicAssessment is false.
        verify(evaluationEngine).evaluatePipeline(any(), any(), any(), any(), isNull(), eq(false));
        verify(pillarEvaluationRepository).saveAll(any());

        ArgumentCaptor<OverallSummary> summaryCaptor = ArgumentCaptor.forClass(OverallSummary.class);
        verify(overallSummaryRepository).save(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getOverallScorePercentage()).isEqualByComparingTo(new BigDecimal("78"));
        assertThat(summaryCaptor.getValue().getSummaryNarrative()).isEqualTo("Good overall performance");

        ArgumentCaptor<Submission> subCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.EVALUATED);
        assertThat(subCaptor.getValue().getEvaluatedAt()).isNotNull();
        // The finished evaluation releases its claim so the column never lingers as
        // a false "in-flight" marker on a completed submission.
        assertThat(subCaptor.getValue().getEvaluationClaimedAt()).isNull();
        // A full (non-partial) evaluation snapshots the prior results to history
        // before overwriting them in place, so a retake can't silently lose them.
        // A null scope means "all pillars" (full re-eval).
        verify(pillarReeditService).archiveEvaluation(
                eq(submissionId), any(), any(), isNull(),
                eq(PillarReeditService.ARCHIVE_REASON_FULL_REEVAL));
    }

    @Test
    void evaluateSubmission_alreadyClaimedByAnotherWorker_skipsEvaluation() {
        // The claim is the authoritative duplicate gate: 0 rows updated = another worker
        // already holds it (or the row is no longer SUBMITTED) → the AI evaluation must NOT
        // run a second time. The submission is loaded first now (for the fairness key), but
        // nothing past the claim runs.
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(submissionRepository.claimForEvaluation(eq(submissionId), anyLong())).thenReturn(0);

        evaluationService.evaluateSubmission(submissionId);

        verifyNoInteractions(evaluationEngine);
        verify(pillarEvaluationRepository, never()).saveAll(any());
    }

    @Test
    void evaluateSubmission_orgFairnessThrottleReached_defersLeavingSubmitted() {
        // The per-org fairness throttle is a dispatch DEFERRAL, not a user-facing failure:
        // when it trips we return BEFORE claiming, so the row stays SUBMITTED with a null
        // claim for the reaper to re-dispatch. The AI evaluation must not run.
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        doThrow(new com.bvisionry.common.exception.RateLimitExceededException("rate limit"))
                .when(rateLimitService).checkEvaluationLimit(anyString());

        evaluationService.evaluateSubmission(submissionId);

        // Deferred before the claim — no claim stamped, no AI run, submission untouched.
        verify(submissionRepository, never()).claimForEvaluation(any(), anyLong());
        verifyNoInteractions(evaluationEngine);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    void retryFailedSubmission_needsReview_dispatchesWithReuseHealthyPillars() {
        // A NEEDS_REVIEW retry with no open unlock window reuses the healthy pillar rows —
        // it re-runs as a DEGRADED_RETRY (reuseHealthyPillars=true) rather than re-billing
        // every pillar.
        EvaluationService selfMock = mock(EvaluationService.class);
        ReflectionTestUtils.setField(evaluationService, "self", selfMock);

        submission.setStatus(SubmissionStatus.NEEDS_REVIEW);
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(pillarReeditService.findUnlockedPillarIds(submissionId)).thenReturn(List.of());

        evaluationService.retryFailedSubmission(submissionId);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        // AfterCommit.dispatch runs immediately outside a transaction; the reuse hint is true.
        verify(selfMock).evaluateSubmissionAsync(submissionId, true);
    }

    @Test
    void retryFailedSubmission_failed_dispatchesFullRun() {
        // A FAILED retry never reuses prior rows (a FAILED usually persisted nothing, and an
        // INPUT retake may have changed the answers) → full run (reuseHealthyPillars=false),
        // and it never consults the NEEDS_REVIEW-only unlock guard.
        EvaluationService selfMock = mock(EvaluationService.class);
        ReflectionTestUtils.setField(evaluationService, "self", selfMock);

        submission.setStatus(SubmissionStatus.FAILED);
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

        evaluationService.retryFailedSubmission(submissionId);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        verify(selfMock).evaluateSubmissionAsync(submissionId, false);
        verify(pillarReeditService, never()).findUnlockedPillarIds(any());
    }

    @Test
    void evaluateSubmission_whenSummaryAlreadyExists_updatesInPlaceNotInsert() {
        // A re-evaluation must update the existing overall_summary row rather than
        // INSERT a new one (the unique submission_id constraint would otherwise
        // throw and the submission would be marked FAILED).
        OverallSummary existing = new OverallSummary();
        existing.setId(UUID.randomUUID());
        existing.setSubmission(submission);

        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(existing));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        ArgumentCaptor<OverallSummary> captor = ArgumentCaptor.forClass(OverallSummary.class);
        verify(overallSummaryRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    @Test
    void evaluateSubmission_pillarFailed_marksNeedsReviewAndSkipsEmail() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildDegradedResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // Fail loud: a pillar the AI couldn't evaluate (even after repair) lands the
        // submission in NEEDS_REVIEW with a reason — never a clean EVALUATED — and the
        // member is not emailed incomplete results.
        ArgumentCaptor<Submission> subCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.NEEDS_REVIEW);
        assertThat(subCaptor.getValue().getFailureReason()).contains("could not be evaluated");
        verify(emailService, never()).sendResultsReady(any(), any(), any(), any(), any(), any());
    }

    private EvaluationEngine.PipelineEvaluationResult buildDegradedResult() {
        EvaluationEngine.PillarResult failedPillar = new EvaluationEngine.PillarResult(
                pillar.getId(), "Leadership", null,
                BigDecimal.ZERO, "Unknown", null, "raw response", null,
                null, "rubric", true);
        EvaluationEngine.SummaryResult summary = new EvaluationEngine.SummaryResult(
                BigDecimal.ZERO, "", List.of(), List.of(),
                null, null, "raw", null, null, false);
        return new EvaluationEngine.PipelineEvaluationResult(List.of(failedPillar), summary);
    }

    @Test
    void evaluateSubmission_partialReevalSucceeds_clearsUnlocks() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        // Unlock rows present → the partial re-eval path runs.
        when(pillarReeditService.findUnlockedPillarIds(submissionId)).thenReturn(List.of(pillar.getId()));
        when(evaluationEngine.evaluatePartialPipeline(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // A clean partial re-eval clears the unlock rows as its final step, and
        // snapshots ONLY the unlocked pillars (scope = the unlocked set) rather than
        // every pillar.
        verify(pillarReeditService).clearUnlocks(submissionId);
        verify(pillarReeditService).archiveEvaluation(
                eq(submissionId), any(), any(), eq(Set.of(pillar.getId())),
                eq(PillarReeditService.ARCHIVE_REASON_PILLAR_REEVAL));
    }

    @Test
    void evaluateSubmission_partialReevalDegrades_keepsUnlocks() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(pillarReeditService.findUnlockedPillarIds(submissionId)).thenReturn(List.of(pillar.getId()));
        when(evaluationEngine.evaluatePartialPipeline(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildDegradedResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // A degraded partial re-eval must leave the unlock rows intact so the admin
        // can retry the re-edit — clearing them would strand the re-edit window
        // (triggerReevaluation requires unlocked pillars to exist).
        verify(pillarReeditService, never()).clearUnlocks(any());
        ArgumentCaptor<Submission> subCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.NEEDS_REVIEW);
    }

    @Test
    void evaluateSubmission_submissionNotSubmitted_skips() {
        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));

        evaluationService.evaluateSubmission(submissionId);

        verify(pillarEvaluationRepository, never()).saveAll(any());
        verify(overallSummaryRepository, never()).save(any());
    }

    @Test
    void evaluateSubmission_sendsResultsReadyEmail() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId)).thenReturn(List.of(likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        verify(emailService).sendResultsReady(
                eq("user@test.com"), eq("Test User"), eq("Test Pipeline"), any(), any(), any());
    }

    @Test
    void evaluateSubmission_personalPillarAnswers_excludedFromEvaluation() {
        Pillar personalPillar = new Pillar();
        personalPillar.setId(UUID.randomUUID());
        personalPillar.setName("General Information");
        personalPillar.setType(PillarType.PERSONAL);
        personalPillar.setWeight(BigDecimal.ZERO);
        personalPillar.setMaturityThresholds(Map.of());
        personalPillar.setPipeline(pipeline);

        Question nameQuestion = new Question();
        nameQuestion.setId(UUID.randomUUID());
        nameQuestion.setType(QuestionType.FREE_TEXT);
        nameQuestion.setPromptText("Your full name");
        nameQuestion.setWeight(BigDecimal.ONE);
        nameQuestion.setPillar(personalPillar);

        Answer nameAnswer = new Answer();
        nameAnswer.setId(UUID.randomUUID());
        nameAnswer.setSubmission(submission);
        nameAnswer.setQuestion(nameQuestion);
        nameAnswer.setResponseText("John Doe");

        pipeline.setPillars(List.of(personalPillar, pillar));

        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(nameAnswer, freeTextAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // evaluatePipeline receives ALL answers — it handles personal filtering internally
        verify(evaluationEngine).evaluatePipeline(eq(pipeline), any(), any(), any(), isNull(), eq(false));
    }

    @Test
    void evaluateSubmission_publicSubmission_usesPublicLinkPipeline_skipsEmailAndAudit() {
        // Anonymous public-link submission: no assignment, no user — the
        // pipeline comes from the public link and there is no org tier.
        PublicAssessmentLink publicLink = new PublicAssessmentLink();
        publicLink.setId(UUID.randomUUID());
        publicLink.setPipeline(pipeline);

        submission.setAssignment(null);
        submission.setUser(null);
        submission.setPublicLink(publicLink);
        submission.setRespondentName("Jane Respondent");

        // Dedicated model configured for public assessments — must reach the engine.
        aiConfiguration.setPublicAssessmentModel("anthropic/claude-haiku-4-5");

        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // Pipeline resolved from the public link, premium (non-free-tier) prompt path,
        // evaluated with the dedicated public-assessment model and the public system
        // prompt (publicAssessment = true).
        verify(evaluationEngine).evaluatePipeline(eq(pipeline), eq(submissionId), any(), any(),
                eq("anthropic/claude-haiku-4-5"), eq(true));
        verify(promptTemplateService).getActivePromptContent(PromptType.OVERALL_SUMMARY);

        // No account email and no org actor — both side channels stay silent.
        verifyNoInteractions(emailService, auditService);

        ArgumentCaptor<Submission> subCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.EVALUATED);
        assertThat(subCaptor.getValue().getEvaluatedAt()).isNotNull();
    }
}
