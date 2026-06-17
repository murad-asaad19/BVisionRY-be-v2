package com.bvisionry.evaluation;

import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.PromptTemplateService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                List.of("Practice delegating tasks"),
                "Strong communicator", "Continue developing delegation", "raw json",
                null, null
        );

        return new EvaluationEngine.PipelineEvaluationResult(List.of(pillarResult), summary);
    }

    @Test
    void evaluateSubmission_processesAllPillars_createsEvaluations() {
        when(submissionRepository.findByIdWithAllRelations(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId))
                .thenReturn(List.of(freeTextAnswer, likertAnswer));
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // Member submissions never carry a model override and use the internal
        // (non-public) system prompt — publicAssessment is false.
        verify(evaluationEngine).evaluatePipeline(any(), any(), any(), any(), anyBoolean(), isNull(), eq(false));
        verify(pillarEvaluationRepository).saveAll(any());

        ArgumentCaptor<OverallSummary> summaryCaptor = ArgumentCaptor.forClass(OverallSummary.class);
        verify(overallSummaryRepository).save(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getOverallScorePercentage()).isEqualByComparingTo(new BigDecimal("78"));
        assertThat(summaryCaptor.getValue().getSummaryNarrative()).isEqualTo("Good overall performance");

        ArgumentCaptor<Submission> subCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.EVALUATED);
        assertThat(subCaptor.getValue().getEvaluatedAt()).isNotNull();
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
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
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
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // evaluatePipeline receives ALL answers — it handles personal filtering internally
        verify(evaluationEngine).evaluatePipeline(eq(pipeline), any(), any(), any(), anyBoolean(), isNull(), eq(false));
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
        when(evaluationEngine.evaluatePipeline(any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(buildMockResult());
        when(aiConfigService.getConfigEntity()).thenReturn(aiConfiguration);
        when(pillarEvaluationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(overallSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluateSubmission(submissionId);

        // Pipeline resolved from the public link, premium (non-free-tier) prompt path,
        // evaluated with the dedicated public-assessment model and the public system
        // prompt (publicAssessment = true).
        verify(evaluationEngine).evaluatePipeline(eq(pipeline), eq(submissionId), any(), any(), eq(false),
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
