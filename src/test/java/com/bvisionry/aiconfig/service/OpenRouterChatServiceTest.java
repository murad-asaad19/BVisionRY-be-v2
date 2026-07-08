package com.bvisionry.aiconfig.service;

import com.bvisionry.aicalllog.dto.AICallLogEntry;
import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aicalllog.service.AICallLogService;
import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.validation.AIResponseValidator;
import com.bvisionry.aiengine.guardrail.SchemaValidationException;
import com.bvisionry.aiengine.service.AiEvaluationEngine;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.enums.AICallStatus;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the orchestration seam over the LangChain4j {@link AiEvaluationEngine}.
 * The engine itself (structured output + guardrail repair) is mocked here; its real
 * behaviour is proven in {@code AiEvaluationEngineRepairTest}. These tests verify the
 * mapping into {@code AIResponse} and the three outcome paths: success, soft
 * validation-failure (→ {@code parsed == null}), and transport error (→ thrown
 * {@link AIServiceException}).
 */
@ExtendWith(MockitoExtension.class)
class OpenRouterChatServiceTest {

    @Mock private AiEvaluationEngine aiEngine;
    @Mock private AIConfigService configService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private AICallLogService callLogService;
    @Mock private AiEvaluationCacheService evalCacheService;

    private OpenRouterChatService chatService;
    private AIConfiguration testConfig;

    @BeforeEach
    void setUp() {
        AIResponseValidator validator = new AIResponseValidator();
        chatService = new OpenRouterChatService(
                aiEngine, configService, promptTemplateService, validator, callLogService,
                new SimpleMeterRegistry(), evalCacheService);

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
    void evaluatePillar_validResponse_returnsCleanedResult() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));
        Result<PillarEvaluationResult> stub = resultOf(new PillarEvaluationResult(
                75, "Good progress.", List.of("Clear vision", "Team alignment"),
                List.of("Data-driven decisions"), "Drives advantage.", List.of()));
        when(aiEngine.evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(stub);

        var response = chatService.evaluatePillar(
                "rubric", "response text", null, null, false, CallMetadata.NONE);

        assertThat(response.parsed().scorePercentage()).isEqualTo(75);
        assertThat(response.parsed().whatsWorking()).hasSize(2);
    }

    @Test
    void evaluatePillar_withModelOverride_passesOverrideToEngine() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));
        Result<PillarEvaluationResult> stub = resultOf(validPillar());
        when(aiEngine.evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(stub);

        chatService.evaluatePillar("rubric", "response", "openai/gpt-4o", null, false, CallMetadata.NONE);

        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(aiEngine).evaluatePillar(anyString(), anyString(), model.capture(), anyDouble(), anyInt());
        assertThat(model.getValue()).isEqualTo("openai/gpt-4o");
    }

    @Test
    void evaluatePillar_repairExhausted_returnsUnparsed_andPersistsEvidence() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));
        when(aiEngine.evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new SchemaValidationException(
                        "schema validation failed after repair retries",
                        "{\"scorePercentage\": 72", null));

        var response = chatService.evaluatePillar("rubric", "response", null, null, false, CallMetadata.NONE);

        // Soft failure: the model never produced valid output even after repair —
        // surfaced as parsed == null (fail-loud handling lands in P3), not a throw.
        assertThat(response.parsed()).isNull();

        // The offending raw output and an enriched message are persisted so the
        // failure is debuggable, rather than a null payload + a static generic message.
        ArgumentCaptor<AICallLogEntry> entry = ArgumentCaptor.forClass(AICallLogEntry.class);
        verify(callLogService).record(entry.capture());
        assertThat(entry.getValue().status()).isEqualTo(AICallStatus.FAILED);
        assertThat(entry.getValue().rawResponse()).isEqualTo("{\"scorePercentage\": 72");
        assertThat(entry.getValue().errorMessage())
                .contains("Model output failed schema validation after repair retries")
                .contains("schema validation failed after repair retries");
    }

    @Test
    void evaluatePillar_transportError_wrapsAsAIServiceException() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));
        when(aiEngine.evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() ->
                chatService.evaluatePillar("rubric", "response", null, null, false, CallMetadata.NONE))
                .isInstanceOf(AIServiceException.class)
                .hasMessageContaining("AI pillar-evaluation call failed");
    }

    @Test
    void generateOverallSummary_validResponse_returnsParsedResult() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an assessment AI."));
        Result<OverallSummaryResult> stub = resultOf(new OverallSummaryResult(
                82, "Strong performance.", List.of("Leadership", "Innovation"),
                List.of("Communication"), "Pattern", "Forward"));
        when(aiEngine.generateOverallSummary(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(stub);

        var response = chatService.generateOverallSummary(
                "Pillar results summary", null, null, null, false, CallMetadata.NONE);

        assertThat(response.parsed().overallScorePercentage()).isEqualTo(82);
        assertThat(response.parsed().strengths()).hasSize(2);
    }

    @Test
    void evaluatePillar_publicAssessment_usesPublicSystemPrompt() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.PUBLIC_ASSESSMENT_SYSTEM_PROMPT))
                .thenReturn(new PromptTemplateResponse(UUID.randomUUID(), UUID.randomUUID(),
                        PromptType.PUBLIC_ASSESSMENT_SYSTEM_PROMPT,
                        "You are a public-assessment analyst.", Instant.now()));
        Result<PillarEvaluationResult> stub = resultOf(validPillar());
        when(aiEngine.evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(stub);

        chatService.evaluatePillar("rubric", "response", null, null, true, CallMetadata.NONE);

        verify(promptTemplateService).getActivePrompt(PromptType.PUBLIC_ASSESSMENT_SYSTEM_PROMPT);
        verify(promptTemplateService, never()).getActivePrompt(eq(PromptType.SYSTEM_PROMPT));
    }

    @Test
    void evaluatePillar_cacheHit_servedWithoutEngineCall() {
        when(configService.getConfigEntity()).thenReturn(testConfig);
        when(promptTemplateService.getActivePrompt(PromptType.SYSTEM_PROMPT))
                .thenReturn(systemPromptResponse("You are an evaluator."));
        when(evalCacheService.isEnabled()).thenReturn(true);
        String cachedJson = "{\"scorePercentage\":88,\"whatThisScoreMeans\":\"Cached result.\","
                + "\"whatsWorking\":[\"Clarity\"],\"whatCanImprove\":[\"Depth\"],"
                + "\"whyThisMattersForBusiness\":\"Drives advantage.\",\"evidence\":[]}";
        when(evalCacheService.lookup(anyString())).thenReturn(Optional.of(cachedJson));

        var response = chatService.evaluatePillar("rubric", "response", null, null, false, CallMetadata.NONE);

        // Served from cache: parsed from the stored JSON, no live engine call, no ai_call_log row,
        // and never re-stored.
        assertThat(response.parsed().scorePercentage()).isEqualTo(88);
        verify(aiEngine, never()).evaluatePillar(anyString(), anyString(), anyString(), anyDouble(), anyInt());
        verify(callLogService, never()).record(any());
        verify(evalCacheService, never()).store(anyString(), anyString(), anyString(), anyString());
    }

    private static PillarEvaluationResult validPillar() {
        return new PillarEvaluationResult(70, "Solid.", List.of("A"), List.of("B"), "Matters.", List.of());
    }

    private static PromptTemplateResponse systemPromptResponse(String content) {
        return new PromptTemplateResponse(UUID.randomUUID(), UUID.randomUUID(),
                PromptType.SYSTEM_PROMPT, content, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> resultOf(T content) {
        Result<T> result = mock(Result.class);
        lenient().when(result.content()).thenReturn(content);
        lenient().when(result.tokenUsage()).thenReturn(null);
        return result;
    }
}
