package com.bvisionry.aiconfig.service;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aicalllog.service.AICallLogService;
import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.validation.AIResponseValidator;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.AIServiceException;
import com.bvisionry.config.AIChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRouterChatServiceTest {

    @Mock
    private AnthropicChatModel chatModel;

    @Mock
    private AIChatModelFactory chatModelFactory;

    @Mock
    private AIConfigService configService;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private AICallLogService callLogService;

    private AIResponseValidator validator;
    private OpenRouterChatService chatService;

    private AIConfiguration testConfig;

    @BeforeEach
    void setUp() {
        validator = new AIResponseValidator();
        when(chatModelFactory.create()).thenReturn(chatModel);
        chatService = new OpenRouterChatService(chatModelFactory, configService, promptTemplateService, validator, callLogService, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        testConfig = new AIConfiguration();
        testConfig.setId(UUID.randomUUID());
        testConfig.setDefaultEvaluationModel("anthropic/claude-sonnet-4");
        testConfig.setDefaultInsightModel("anthropic/claude-sonnet-4");
        testConfig.setEvaluationTemperature(new BigDecimal("0.30"));
        testConfig.setInsightTemperature(new BigDecimal("0.70"));
        testConfig.setMaxTokensEvaluation(2048);
        testConfig.setMaxTokensInsight(4096);
    }

    @Test
    void evaluatePillar_validResponse_returnsParsedResult() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator. {{RUBRIC_INSTRUCTIONS}}"));

        String aiResponseJson = """
                {
                    "scorePercentage": 75,
                    "whatThisScoreMeans": "Good progress in strategy execution.",
                    "whatsWorking": ["Clear vision", "Team alignment"],
                    "whatCanImprove": ["Data-driven decisions", "Documentation"],
                    "whyThisMattersForBusiness": "Drives sustainable competitive advantage."
                }
                """;

        mockChatResponse(aiResponseJson);

        OpenRouterChatService.AIResponse<PillarEvaluationResult> response = chatService.evaluatePillar(
                "Evaluate based on strategic thinking criteria", "Our team has implemented a comprehensive strategy...", null,
                null, CallMetadata.NONE);

        assertThat(response.parsed().scorePercentage()).isEqualTo(75);
        assertThat(response.parsed().whatsWorking()).hasSize(2);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void evaluatePillar_withModelOverride_usesOverrideModel() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator. {{RUBRIC_INSTRUCTIONS}}"));

        String aiResponseJson = """
                {
                    "scorePercentage": 60,
                    "whatThisScoreMeans": "Adequate performance.",
                    "whatsWorking": ["Consistency"],
                    "whatCanImprove": ["Innovation"],
                    "whyThisMattersForBusiness": "Enables growth."
                }
                """;

        mockChatResponse(aiResponseJson);

        OpenRouterChatService.AIResponse<PillarEvaluationResult> response = chatService.evaluatePillar(
                "rubric", "response text", "openai/gpt-4o",
                null, CallMetadata.NONE);

        assertThat(response.parsed().scorePercentage()).isEqualTo(60);
    }

    @Test
    void evaluatePillar_invalidJsonResponse_returnsUnparsed() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));

        mockChatResponse("This is not valid JSON at all");

        var response = chatService.evaluatePillar("rubric", "response", null, null, CallMetadata.NONE);
        assertThat(response.parsed()).isNull();
        assertThat(response.rawResponse()).isEqualTo("This is not valid JSON at all");
    }

    @Test
    void evaluatePillar_scoreOutOfRange_returnsUnparsed() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));

        String aiResponseJson = """
                {
                    "scorePercentage": 150,
                    "whatThisScoreMeans": "Explanation",
                    "whatsWorking": ["Good"],
                    "whatCanImprove": ["Better"],
                    "whyThisMattersForBusiness": "Matters"
                }
                """;

        mockChatResponse(aiResponseJson);

        var response = chatService.evaluatePillar("rubric", "response", null, null, CallMetadata.NONE);
        assertThat(response.parsed()).isNull();
    }

    @Test
    void generateOverallSummary_validResponse_returnsParsedResult() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an assessment AI."));

        String aiResponseJson = """
                {
                    "overallScorePercentage": 82,
                    "summaryNarrative": "Strong performance across pillars.",
                    "strengths": ["Leadership", "Innovation"],
                    "developmentAreas": ["Communication"],
                    "recommendations": ["Invest in team building"]
                }
                """;

        mockChatResponse(aiResponseJson);

        OpenRouterChatService.AIResponse<OverallSummaryResult> response =
                chatService.generateOverallSummary("Pillar results summary text", null, null, false, null, CallMetadata.NONE);

        assertThat(response.parsed().overallScorePercentage()).isEqualTo(82);
        assertThat(response.parsed().strengths()).hasSize(2);
        assertThat(response.parsed().recommendations()).contains("Invest in team building");
    }

    @Test
    void evaluatePillar_chatModelThrows_wrapsAsAIServiceException() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator. {{RUBRIC_INSTRUCTIONS}}"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> chatService.evaluatePillar("rubric", "response", null, null, CallMetadata.NONE))
                .isInstanceOf(AIServiceException.class)
                .hasMessageContaining("AI pillar-evaluation call failed");
    }

    private static PromptTemplateResponse systemPromptResponse(String content) {
        return new PromptTemplateResponse(UUID.randomUUID(), PromptType.SYSTEM_PROMPT, content, Instant.now());
    }

    private void mockChatResponse(String content) {
        AssistantMessage assistantMessage = new AssistantMessage(content);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }
}
