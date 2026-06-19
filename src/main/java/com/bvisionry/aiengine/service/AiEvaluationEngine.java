package com.bvisionry.aiengine.service;

import com.bvisionry.aiengine.guardrail.StructuredOutputGuardrail;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.dto.TeamInsightResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds and invokes the typed LangChain4j AI services that produce the
 * evaluation outputs. This is where the model-agnostic robustness lives:
 *
 * <ul>
 *   <li>The typed return drives structured output — native JSON schema when the
 *       configured model supports it (decided in {@link Lc4jChatModelProvider}),
 *       prompt-instructed JSON otherwise.</li>
 *   <li>{@link StructuredOutputGuardrail} validates each response and reprompts
 *       the model on malformed / incomplete / out-of-range output, up to
 *       {@code bvisionry.ai.repair-retries} attempts — the repair loop.</li>
 * </ul>
 *
 * <p>Services are rebuilt per call because the system prompt is dynamic (DB-backed,
 * admin-editable); the underlying {@link ChatModel} is cached by the provider.
 */
@Component
public class AiEvaluationEngine {

    private final Lc4jChatModelProvider modelProvider;
    private final AiResilience aiResilience;
    private final int repairRetries;

    /** Local mapper for guardrail JSON tree inspection. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Narrative fields a pillar evaluation must carry to be useful. Listed as guardrail
     * required-fields so a model that returns only a bare {@code scorePercentage} (and
     * lets the DTO compact constructor backfill empty narrative) is repromptted rather
     * than silently accepted.
     */
    private static final List<String> PILLAR_REQUIRED_FIELDS =
            List.of("whatThisScoreMeans", "whatsWorking", "whatCanImprove");

    public AiEvaluationEngine(Lc4jChatModelProvider modelProvider,
                              AiResilience aiResilience,
                              @Value("${bvisionry.ai.repair-retries:2}") int repairRetries) {
        this.modelProvider = modelProvider;
        this.aiResilience = aiResilience;
        this.repairRetries = repairRetries;
    }

    public Result<PillarEvaluationResult> evaluatePillar(String systemPrompt, String userMessage,
                                                         String model, double temperature, int maxTokens) {
        PillarEvaluator service = AiServices.builder(PillarEvaluator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(new StructuredOutputGuardrail(MAPPER, PILLAR_REQUIRED_FIELDS, "scorePercentage"))
                .outputGuardrailsConfig(retryConfig())
                .build();
        return aiResilience.execute(() -> service.evaluate(userMessage));
    }

    public Result<OverallSummaryResult> generateOverallSummary(String systemPrompt, String userMessage,
                                                              String model, double temperature, int maxTokens) {
        SummaryGenerator service = AiServices.builder(SummaryGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(new StructuredOutputGuardrail(MAPPER, List.of(), "overallScorePercentage"))
                .outputGuardrailsConfig(retryConfig())
                .build();
        return aiResilience.execute(() -> service.generate(userMessage));
    }

    public Result<TeamInsightResult> generateTeamInsight(String systemPrompt, String userMessage,
                                                        String model, double temperature, int maxTokens) {
        TeamInsightGenerator service = AiServices.builder(TeamInsightGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(new StructuredOutputGuardrail(MAPPER, List.of("teamThemes"), null))
                .outputGuardrailsConfig(retryConfig())
                .build();
        return aiResilience.execute(() -> service.generate(userMessage));
    }

    private ChatModel modelFor(String model, double temperature, int maxTokens) {
        return modelProvider.modelFor(model, temperature, maxTokens);
    }

    private OutputGuardrailsConfig retryConfig() {
        return OutputGuardrailsConfig.builder()
                .maxRetries(repairRetries)
                .build();
    }
}
