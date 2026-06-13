package com.bvisionry.assessment;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SaveAnswerRequest;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.web.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssessmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
class AssessmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @MockitoBean
    private AssessmentService assessmentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    private final UUID userId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUpSecurityContext() {
        User user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setName("Test User");
        user.setRole(UserRole.MEMBER);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAssessments_returns200() throws Exception {
        AssessmentSummaryResponse summary = new AssessmentSummaryResponse(
                submissionId, UUID.randomUUID(), UUID.randomUUID(),
                "Test Pipeline", "Description",
                SubmissionStatus.IN_PROGRESS, null, 10, 3,
                Instant.now(), null, null, 1, 1, true);

        when(assessmentService.listAssessments(userId)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/my/assessments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pipelineName").value("Test Pipeline"))
                .andExpect(jsonPath("$[0].totalQuestions").value(10))
                .andExpect(jsonPath("$[0].answeredQuestions").value(3));
    }

    @Test
    void getAssessment_returns200() throws Exception {
        AssessmentDetailResponse detail = new AssessmentDetailResponse(
                submissionId, UUID.randomUUID(),
                SubmissionStatus.IN_PROGRESS, null,
                new AssessmentDetailResponse.PipelineInfo(UUID.randomUUID(), "Pipeline", "Desc"),
                List.of(),
                List.of());

        when(assessmentService.getAssessment(submissionId, userId)).thenReturn(detail);

        mockMvc.perform(get("/api/my/assessments/{submissionId}", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));
    }

    @Test
    void saveAnswer_returns200() throws Exception {
        UUID questionId = UUID.randomUUID();
        SaveAnswerRequest request = new SaveAnswerRequest("My response", null);

        AnswerResponse response = new AnswerResponse(
                UUID.randomUUID(), questionId, "My response", null, Instant.now());

        when(assessmentService.saveAnswer(eq(submissionId), eq(questionId), eq(userId), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/my/assessments/{submissionId}/answers/{questionId}",
                        submissionId, questionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText").value("My response"));
    }

    @Test
    void batchSaveAnswers_returns200() throws Exception {
        UUID questionId = UUID.randomUUID();
        BatchSaveAnswersRequest request = new BatchSaveAnswersRequest(List.of(
                new BatchSaveAnswersRequest.AnswerEntry(questionId, "Response 1", null)));

        AnswerResponse answerResp = new AnswerResponse(
                UUID.randomUUID(), questionId, "Response 1", null, Instant.now());

        when(assessmentService.batchSaveAnswers(eq(submissionId), eq(userId), any()))
                .thenReturn(List.of(answerResp));

        mockMvc.perform(post("/api/my/assessments/{submissionId}/answers/batch", submissionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].responseText").value("Response 1"));
    }

    @Test
    void getReview_returns200() throws Exception {
        ReviewResponse review = new ReviewResponse(submissionId, true, 5, 5, List.of());

        when(assessmentService.getReview(submissionId, userId)).thenReturn(review);

        mockMvc.perform(get("/api/my/assessments/{submissionId}/review", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.totalRequired").value(5));
    }

    @Test
    void submitAssessment_returns200() throws Exception {
        SubmitAssessmentResponse response = new SubmitAssessmentResponse(
                submissionId, SubmissionStatus.SUBMITTED, Instant.now(),
                "Assessment submitted successfully.", null);

        when(assessmentService.submitAssessment(submissionId, userId)).thenReturn(response);

        mockMvc.perform(post("/api/my/assessments/{submissionId}/submit", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void getStatus_returns200() throws Exception {
        SubmissionStatusResponse statusResp = new SubmissionStatusResponse(
                submissionId, SubmissionStatus.EVALUATED, Instant.now(), Instant.now());

        when(assessmentService.getStatus(submissionId, userId)).thenReturn(statusResp);

        mockMvc.perform(get("/api/my/assessments/{submissionId}/status", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVALUATED"));
    }
}
