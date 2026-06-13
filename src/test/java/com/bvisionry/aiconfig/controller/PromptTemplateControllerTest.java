package com.bvisionry.aiconfig.controller;

import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.dto.PromptTemplateUpdateRequest;
import com.bvisionry.aiconfig.dto.TryItOutRequest;
import com.bvisionry.aiconfig.service.PromptTemplateService;
import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.evaluation.EvaluationEngine;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PillarRepository;
import com.bvisionry.common.enums.QuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PromptTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
class PromptTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @MockitoBean
    private PromptTemplateService promptService;

    @MockitoBean
    private EvaluationEngine evaluationEngine;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private PillarRepository pillarRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void getAllActivePrompts_returnsList() throws Exception {
        List<PromptTemplateResponse> prompts = List.of(
                new PromptTemplateResponse(UUID.randomUUID(), PromptType.SYSTEM_PROMPT,
                        "content", Instant.now()),
                new PromptTemplateResponse(UUID.randomUUID(), PromptType.TEAM_INSIGHT,
                        "content", Instant.now())
        );

        when(promptService.getAllActivePrompts()).thenReturn(prompts);

        mockMvc.perform(get("/api/ai-config/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getActivePromptByType_returnsPrompt() throws Exception {
        PromptTemplateResponse response = new PromptTemplateResponse(
                UUID.randomUUID(), PromptType.SYSTEM_PROMPT,
                "Evaluation content", Instant.now()
        );

        when(promptService.getActivePrompt(PromptType.SYSTEM_PROMPT)).thenReturn(response);

        mockMvc.perform(get("/api/ai-config/prompts/SYSTEM_PROMPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptType").value("SYSTEM_PROMPT"));
    }

    @Test
    void updatePrompt_validRequest_returnsUpdated() throws Exception {
        PromptTemplateUpdateRequest request = new PromptTemplateUpdateRequest(
                "Updated evaluation prompt content that is long enough"
        );

        PromptTemplateResponse response = new PromptTemplateResponse(
                UUID.randomUUID(), PromptType.SYSTEM_PROMPT,
                "Updated evaluation prompt content that is long enough",
                Instant.now()
        );

        when(promptService.updatePrompt(eq(PromptType.SYSTEM_PROMPT), any())).thenReturn(response);

        mockMvc.perform(put("/api/ai-config/prompts/SYSTEM_PROMPT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Updated evaluation prompt content that is long enough"));
    }

    @Test
    void tryItOut_validRequest_returnsEvaluation() throws Exception {
        UUID pillarId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();

        // Build pillar with a question
        Pillar pillar = new Pillar();
        pillar.setId(pillarId);
        pillar.setType(PillarType.STANDARD);
        pillar.setAiRubricInstructions("Evaluate based on strategic thinking");
        pillar.setMaturityThresholds(Map.of("Emerging", List.of(0, 59), "Strong", List.of(60, 79), "Elite", List.of(80, 100)));

        Question question = new Question();
        question.setId(questionId);
        question.setType(QuestionType.FREE_TEXT);
        question.setPromptText("Describe your strategy");
        question.setWeight(BigDecimal.ONE);
        question.setPillar(pillar);
        pillar.setQuestions(List.of(question));

        when(pillarRepository.findByIdWithQuestions(pillarId)).thenReturn(Optional.of(pillar));

        PillarEvaluationResult evalResult = new PillarEvaluationResult(
                78, "Good strategy thinking",
                List.of("Clear vision"), List.of("Data analysis"),
                "Drives competitive advantage",
                List.of()
        );

        EvaluationEngine.PillarResult pillarResult = new EvaluationEngine.PillarResult(
                pillarId, "Test", null,
                new BigDecimal("78"), "Strong",
                evalResult, "raw json", null,
                null, null, false
        );

        when(evaluationEngine.evaluatePillar(any(), any())).thenReturn(pillarResult);

        Map<String, TryItOutRequest.AnswerInput> answers = Map.of(
                questionId.toString(), new TryItOutRequest.AnswerInput("My strategic response", null)
        );
        TryItOutRequest request = new TryItOutRequest(pillarId, answers, null);

        mockMvc.perform(post("/api/ai-config/try-it-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.scorePercentage").value(78));
    }

    @Test
    void tryItOut_pillarNotFound_returns404() throws Exception {
        UUID pillarId = UUID.randomUUID();
        TryItOutRequest request = new TryItOutRequest(pillarId, Map.of(), null);

        when(pillarRepository.findByIdWithQuestions(pillarId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/ai-config/try-it-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
