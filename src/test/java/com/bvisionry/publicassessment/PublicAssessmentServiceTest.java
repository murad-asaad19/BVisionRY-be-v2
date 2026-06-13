package com.bvisionry.publicassessment;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionRequest;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionResponse;
import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
import com.bvisionry.publicassessment.entity.PublicAssessmentLinkStatus;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import com.bvisionry.publicassessment.service.PublicAssessmentService;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import com.bvisionry.reporting.service.MemberResultsService;
import com.bvisionry.reporting.service.PersonalInfoResolver;
import com.bvisionry.survey.entity.RespondentFieldMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicAssessmentServiceTest {

    @Mock private PublicAssessmentLinkRepository linkRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private AnswerRepository answerRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private EvaluationService evaluationService;
    @Mock private MemberResultsService memberResultsService;
    @Mock private PillarEvaluationRepository pillarEvaluationRepository;
    @Mock private OverallSummaryRepository overallSummaryRepository;
    @Mock private PersonalInfoResolver personalInfoResolver;

    @InjectMocks
    private PublicAssessmentService publicAssessmentService;

    private UUID token;
    private UUID linkId;
    private UUID accessToken;
    private UUID submissionId;
    private UUID questionId;
    private PublicAssessmentLink link;
    private Pipeline pipeline;
    private Question question;
    private Submission submission;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID();
        linkId = UUID.randomUUID();
        accessToken = UUID.randomUUID();
        submissionId = UUID.randomUUID();
        questionId = UUID.randomUUID();

        pipeline = new Pipeline();
        pipeline.setId(UUID.randomUUID());
        pipeline.setName("Founder Readiness");
        pipeline.setDescription("A public pipeline");

        Pillar pillar = new Pillar();
        pillar.setId(UUID.randomUUID());
        pillar.setName("Leadership");
        pillar.setDisplayOrder(0);
        pillar.setPipeline(pipeline);

        question = new Question();
        question.setId(questionId);
        question.setType(QuestionType.FREE_TEXT);
        question.setPromptText("Describe your leadership style");
        question.setDisplayOrder(0);
        question.setRequired(true);
        question.setWeight(BigDecimal.ONE);
        question.setPillar(pillar);
        pillar.setQuestions(List.of(question));
        pipeline.setPillars(List.of(pillar));

        link = new PublicAssessmentLink();
        link.setId(linkId);
        link.setToken(token);
        link.setPipeline(pipeline);
        link.setTitle("Public Founder Readiness");
        link.setStatus(PublicAssessmentLinkStatus.ACTIVE);
        link.setRespondentEmailMode(RespondentFieldMode.OPTIONAL);
        link.setRespondentNameMode(RespondentFieldMode.OPTIONAL);
        link.setShowResultsToRespondent(true);

        submission = new Submission();
        submission.setId(submissionId);
        submission.setPublicLink(link);
        submission.setAccessToken(accessToken);
        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        submission.setStartedAt(Instant.now());
    }

    @Nested
    class CreateSession {

        @Test
        void createSession_activeLink_mintsAccessTokenAndIncrementsCount() {
            when(linkRepository.findByToken(token)).thenReturn(Optional.of(link));
            when(linkRepository.incrementResponseCount(linkId)).thenReturn(1);
            when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
                Submission s = inv.getArgument(0);
                s.setId(submissionId);
                return s;
            });

            PublicAssessmentSessionResponse response = publicAssessmentService.createSession(
                    token, new PublicAssessmentSessionRequest("jane@example.com", "  Jane Doe  "));

            ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
            verify(submissionRepository).save(captor.capture());
            Submission saved = captor.getValue();

            assertThat(saved.getPublicLink()).isSameAs(link);
            assertThat(saved.getAssignment()).isNull();
            assertThat(saved.getUser()).isNull();
            assertThat(saved.getRespondentEmail()).isEqualTo("jane@example.com");
            assertThat(saved.getRespondentName()).isEqualTo("Jane Doe");
            assertThat(saved.getAccessToken()).isNotNull();

            assertThat(response.submissionId()).isEqualTo(submissionId);
            assertThat(response.accessToken()).isEqualTo(saved.getAccessToken());
            verify(linkRepository).incrementResponseCount(linkId);
        }

        @Test
        void createSession_requiredEmailMissing_throwsBadRequest() {
            link.setRespondentEmailMode(RespondentFieldMode.REQUIRED);
            when(linkRepository.findByToken(token)).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> publicAssessmentService.createSession(
                    token, new PublicAssessmentSessionRequest(null, "Jane Doe")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("email");

            verify(linkRepository, never()).incrementResponseCount(any());
            verify(submissionRepository, never()).save(any());
        }

        @Test
        void createSession_requiredNameMissing_throwsBadRequest() {
            link.setRespondentNameMode(RespondentFieldMode.REQUIRED);
            when(linkRepository.findByToken(token)).thenReturn(Optional.of(link));

            // Blank-only input counts as missing, same as null.
            assertThatThrownBy(() -> publicAssessmentService.createSession(
                    token, new PublicAssessmentSessionRequest("jane@example.com", "   ")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("name");

            verify(linkRepository, never()).incrementResponseCount(any());
            verify(submissionRepository, never()).save(any());
        }

        @Test
        void createSession_disabledLink_throwsGone() {
            link.setStatus(PublicAssessmentLinkStatus.DISABLED);
            when(linkRepository.findByToken(token)).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> publicAssessmentService.createSession(
                    token, new PublicAssessmentSessionRequest(null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.GONE);

            verify(submissionRepository, never()).save(any());
        }

        @Test
        void createSession_capReached_throwsConflict() {
            when(linkRepository.findByToken(token)).thenReturn(Optional.of(link));
            // Atomic cap gate: 0 rows updated = maxResponses already reached.
            when(linkRepository.incrementResponseCount(linkId)).thenReturn(0);

            assertThatThrownBy(() -> publicAssessmentService.createSession(
                    token, new PublicAssessmentSessionRequest(null, null)))
                    .isInstanceOf(IllegalOperationException.class)
                    .hasMessageContaining("maximum number of responses");

            verify(submissionRepository, never()).save(any());
        }
    }

    @Nested
    class Submit {

        @Test
        void submit_allRequiredAnswered_flipsSubmittedAndSchedulesEvaluation() {
            Answer answer = new Answer();
            answer.setId(UUID.randomUUID());
            answer.setSubmission(submission);
            answer.setQuestion(question);
            answer.setResponseText("My answer");

            when(submissionRepository.findByAccessTokenWithPublicLink(accessToken))
                    .thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(answer));
            when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

            SubmitAssessmentResponse response = publicAssessmentService.submit(accessToken);

            assertThat(response.status()).isEqualTo(SubmissionStatus.SUBMITTED);
            assertThat(response.submittedAt()).isNotNull();
            assertThat(response.postCompletion()).isNull();
            assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
            // AfterCommit.run executes immediately outside a transaction.
            verify(evaluationService).evaluateSubmissionAsync(submissionId);
        }

        @Test
        void submit_missingRequiredAnswer_throwsBadRequest() {
            when(submissionRepository.findByAccessTokenWithPublicLink(accessToken))
                    .thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

            assertThatThrownBy(() -> publicAssessmentService.submit(accessToken))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("unanswered required");

            verify(submissionRepository, never()).save(any());
            verify(evaluationService, never()).evaluateSubmissionAsync(any());
        }
    }

    @Nested
    class GetResults {

        @Test
        void getResults_notEvaluated_throwsNotFound() {
            submission.setStatus(SubmissionStatus.SUBMITTED);
            when(submissionRepository.findByAccessTokenWithPublicLink(accessToken))
                    .thenReturn(Optional.of(submission));

            assertThatThrownBy(() -> publicAssessmentService.getResults(accessToken))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getResults_resultsHiddenFromRespondent_throwsNotFound() {
            submission.setStatus(SubmissionStatus.EVALUATED);
            link.setShowResultsToRespondent(false);
            when(submissionRepository.findByAccessTokenWithPublicLink(accessToken))
                    .thenReturn(Optional.of(submission));

            assertThatThrownBy(() -> publicAssessmentService.getResults(accessToken))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getResults_evaluatedAndVisible_returnsResults() {
            submission.setStatus(SubmissionStatus.EVALUATED);
            submission.setEvaluatedAt(Instant.now());

            OverallSummary summary = new OverallSummary();
            summary.setOverallScorePercentage(new BigDecimal("82.00"));
            summary.setSummaryNarrative("Strong overall performance");
            summary.setStrengths(List.of("Vision"));
            summary.setDevelopmentAreas(List.of("Delegation"));
            summary.setRecommendations(List.of("Practice delegation"));

            List<PillarEvaluation> evaluations = List.of(new PillarEvaluation());
            PillarScoreSummary pillarScore = new PillarScoreSummary(
                    UUID.randomUUID(), "Leadership", "leadership",
                    new BigDecimal("82.00"), "Elite");

            when(submissionRepository.findByAccessTokenWithPublicLink(accessToken))
                    .thenReturn(Optional.of(submission));
            when(overallSummaryRepository.findBySubmissionId(submissionId))
                    .thenReturn(Optional.of(summary));
            when(pillarEvaluationRepository.findBySubmissionId(submissionId)).thenReturn(evaluations);
            when(memberResultsService.toPillarScores(evaluations)).thenReturn(List.of(pillarScore));
            when(personalInfoResolver.resolve(submissionId)).thenReturn(List.of());

            MemberResultsResponse result = publicAssessmentService.getResults(accessToken);

            assertThat(result.submissionId()).isEqualTo(submissionId);
            assertThat(result.pipelineName()).isEqualTo("Founder Readiness");
            assertThat(result.overallScore()).isEqualByComparingTo(new BigDecimal("82.00"));
            assertThat(result.pillarScores()).containsExactly(pillarScore);
            assertThat(result.premiumFeaturesAvailable()).isTrue();
            assertThat(result.postCompletion()).isNull();
            assertThat(result.surveyResponse()).isNull();
            assertThat(result.survey()).isNull();
        }
    }

    @Nested
    class DeleteLink {

        @Test
        void deleteLink_withExistingResponses_throwsConflict() {
            when(linkRepository.findById(linkId)).thenReturn(Optional.of(link));
            when(submissionRepository.countByPublicLinkId(linkId)).thenReturn(3L);

            assertThatThrownBy(() -> publicAssessmentService.deleteLink(linkId))
                    .isInstanceOf(IllegalOperationException.class)
                    .hasMessageContaining("has responses");

            verify(linkRepository, never()).delete(any());
        }

        @Test
        void deleteLink_withoutResponses_deletes() {
            when(linkRepository.findById(linkId)).thenReturn(Optional.of(link));
            when(submissionRepository.countByPublicLinkId(linkId)).thenReturn(0L);

            publicAssessmentService.deleteLink(linkId);

            verify(linkRepository).delete(link);
        }
    }
}
