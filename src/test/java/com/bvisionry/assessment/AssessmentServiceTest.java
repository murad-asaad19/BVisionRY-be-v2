package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SaveAnswerRequest;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock private SubmissionRepository submissionRepository;
    @Mock private AnswerRepository answerRepository;
    @Mock private EvaluationService evaluationService;
    @Mock private PostCompletionLinkResolver postCompletionLinkResolver;
    @Mock private com.bvisionry.audit.AuditService auditService;

    @InjectMocks
    private AssessmentService assessmentService;

    private UUID userId;
    private UUID submissionId;
    private UUID questionId;
    private Submission submission;
    private Assignment assignment;
    private Pipeline pipeline;
    private Pillar pillar;
    private Question question;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        submissionId = UUID.randomUUID();
        questionId = UUID.randomUUID();

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        pipeline = new Pipeline();
        pipeline.setId(UUID.randomUUID());
        pipeline.setName("Test Pipeline");
        pipeline.setDescription("A test pipeline");

        pillar = new Pillar();
        pillar.setId(UUID.randomUUID());
        pillar.setName("Leadership");
        pillar.setDescription("Leadership pillar");
        pillar.setIconKey("leadership");
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

        User user = new User();
        user.setId(userId);

        assignment = new Assignment();
        assignment.setId(UUID.randomUUID());
        assignment.setPipeline(pipeline);
        assignment.setOrganization(org);

        submission = new Submission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(user);
        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        submission.setStartedAt(Instant.now());
    }

    @Nested
    class ListAssessments {
        @Test
        void listAssessments_returnsSummaries() {
            when(submissionRepository.findByUserIdOrderByCreatedAtDesc(userId))
                    .thenReturn(List.of(submission));
            when(answerRepository.findAnsweredCountsBySubmissionIds(List.of(submissionId)))
                    .thenReturn(List.of());

            List<AssessmentSummaryResponse> result = assessmentService.listAssessments(userId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).pipelineName()).isEqualTo("Test Pipeline");
            assertThat(result.get(0).totalQuestions()).isEqualTo(1);
            assertThat(result.get(0).answeredQuestions()).isEqualTo(0);
        }
    }

    @Nested
    class GetAssessment {
        @Test
        void getAssessment_returnsDetailWithAnswers() {
            Answer answer = new Answer();
            answer.setId(UUID.randomUUID());
            answer.setQuestion(question);
            answer.setSubmission(submission);
            answer.setResponseText("My leadership approach...");

            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(answer));

            AssessmentDetailResponse result = assessmentService.getAssessment(submissionId, userId);

            assertThat(result.submissionId()).isEqualTo(submissionId);
            assertThat(result.pillars()).hasSize(1);
            assertThat(result.pillars().get(0).questions()).hasSize(1);
            assertThat(result.pillars().get(0).questions().get(0).answer()).isNotNull();
            assertThat(result.pillars().get(0).questions().get(0).answer().responseText())
                    .isEqualTo("My leadership approach...");
        }

        @Test
        void getAssessment_wrongUser_throwsNotFound() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

            UUID otherUserId = UUID.randomUUID();
            assertThatThrownBy(() -> assessmentService.getAssessment(submissionId, otherUserId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class SaveAnswer {
        @Test
        void saveAnswer_createsNewAnswer() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionIdAndQuestionId(submissionId, questionId))
                    .thenReturn(Optional.empty());
            when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> {
                Answer a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

            SaveAnswerRequest request = new SaveAnswerRequest("My response text", null);
            AnswerResponse result = assessmentService.saveAnswer(submissionId, questionId, userId, request);

            assertThat(result).isNotNull();
            assertThat(result.responseText()).isEqualTo("My response text");
            verify(answerRepository).save(any(Answer.class));
        }

        @Test
        void saveAnswer_updatesExistingAnswer() {
            Answer existing = new Answer();
            existing.setId(UUID.randomUUID());
            existing.setSubmission(submission);
            existing.setQuestion(question);
            existing.setResponseText("Old response");

            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionIdAndQuestionId(submissionId, questionId))
                    .thenReturn(Optional.of(existing));
            when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));

            SaveAnswerRequest request = new SaveAnswerRequest("Updated response", null);
            AnswerResponse result = assessmentService.saveAnswer(submissionId, questionId, userId, request);

            assertThat(result.responseText()).isEqualTo("Updated response");
        }

        @Test
        void saveAnswer_submittedSubmission_throwsBadRequest() {
            submission.setStatus(SubmissionStatus.SUBMITTED);
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

            SaveAnswerRequest request = new SaveAnswerRequest("text", null);
            assertThatThrownBy(() -> assessmentService.saveAnswer(submissionId, questionId, userId, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already submitted");
        }
    }

    @Nested
    class BatchSave {
        @Test
        void batchSaveAnswers_savesMultiple() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionIdAndQuestionId(eq(submissionId), any()))
                    .thenReturn(Optional.empty());
            when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> {
                Answer a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

            BatchSaveAnswersRequest request = new BatchSaveAnswersRequest(List.of(
                    new BatchSaveAnswersRequest.AnswerEntry(questionId, "Response 1", null)
            ));

            List<AnswerResponse> result = assessmentService.batchSaveAnswers(submissionId, userId, request);

            assertThat(result).hasSize(1);
            verify(answerRepository, times(1)).save(any(Answer.class));
        }
    }

    @Nested
    class ReviewAndSubmit {
        @Test
        void getReview_allAnswered_returnsComplete() {
            Answer answer = new Answer();
            answer.setId(UUID.randomUUID());
            answer.setQuestion(question);
            answer.setSubmission(submission);
            answer.setResponseText("My answer");

            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(answer));

            ReviewResponse result = assessmentService.getReview(submissionId, userId);

            assertThat(result.complete()).isTrue();
            assertThat(result.totalRequired()).isEqualTo(1);
            assertThat(result.answeredRequired()).isEqualTo(1);
            assertThat(result.unansweredQuestions()).isEmpty();
        }

        @Test
        void getReview_missingRequired_returnsIncomplete() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

            ReviewResponse result = assessmentService.getReview(submissionId, userId);

            assertThat(result.complete()).isFalse();
            assertThat(result.unansweredQuestions()).hasSize(1);
            assertThat(result.unansweredQuestions().get(0).questionId()).isEqualTo(questionId);
        }

        @Test
        void submitAssessment_allRequired_locksAndEnqueues() {
            Answer answer = new Answer();
            answer.setId(UUID.randomUUID());
            answer.setQuestion(question);
            answer.setSubmission(submission);
            answer.setResponseText("My answer");

            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(answer));
            when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

            SubmitAssessmentResponse result = assessmentService.submitAssessment(submissionId, userId);

            assertThat(result.status()).isEqualTo(SubmissionStatus.SUBMITTED);
            assertThat(result.submittedAt()).isNotNull();
            verify(evaluationService).evaluateSubmissionAsync(submissionId);
        }

        @Test
        void submitAssessment_missingRequired_throwsBadRequest() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
            when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

            assertThatThrownBy(() -> assessmentService.submitAssessment(submissionId, userId))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("unanswered required");
        }

        @Test
        void submitAssessment_alreadySubmitted_throwsBadRequest() {
            submission.setStatus(SubmissionStatus.SUBMITTED);
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

            assertThatThrownBy(() -> assessmentService.submitAssessment(submissionId, userId))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("currently being evaluated");
        }
    }

    @Nested
    class PollStatus {
        @Test
        void getStatus_returnsCurrentStatus() {
            when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

            SubmissionStatusResponse result = assessmentService.getStatus(submissionId, userId);

            assertThat(result.submissionId()).isEqualTo(submissionId);
            assertThat(result.status()).isEqualTo(SubmissionStatus.IN_PROGRESS);
        }
    }
}
