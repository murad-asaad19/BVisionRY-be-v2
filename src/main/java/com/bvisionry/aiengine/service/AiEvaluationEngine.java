package com.bvisionry.aiengine.service;

import com.bvisionry.aiengine.guardrail.SchemaValidationException;
import com.bvisionry.aiengine.guardrail.StructuredOutputGuardrail;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.dto.TeamInsightResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.guardrail.OutputGuardrailException;
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

    /**
     * Narrative fields an overall summary must carry to be useful. Listed as guardrail
     * required-fields so a model that returns only a bare {@code overallScorePercentage}
     * (and lets the DTO compact constructor backfill empty narrative) is repromptted rather
     * than silently accepted and rendered as blank sections under a clean EVALUATED.
     */
    private static final List<String> SUMMARY_REQUIRED_FIELDS =
            List.of("summaryNarrative", "strengths", "developmentAreas", "corePattern", "movingForward");

    public AiEvaluationEngine(Lc4jChatModelProvider modelProvider,
                              AiResilience aiResilience,
                              @Value("${bvisionry.ai.repair-retries:2}") int repairRetries) {
        this.modelProvider = modelProvider;
        this.aiResilience = aiResilience;
        this.repairRetries = repairRetries;
    }

    public Result<PillarEvaluationResult> evaluatePillar(String systemPrompt, String userMessage,
                                                         String model, double temperature, int maxTokens) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, PILLAR_REQUIRED_FIELDS, "scorePercentage");
        PillarEvaluator service = AiServices.builder(PillarEvaluator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        // Translate a content failure into SchemaValidationException so the offending
        // model output travels to the caller for audit persistence. The catch MUST stay
        // OUTSIDE aiResilience.execute (around it), never inside the supplier: AiResilience's
        // circuit breaker is configured with ignoreExceptions(OutputGuardrailException.class)
        // and must keep seeing the ORIGINAL exception type so content failures don't trip
        // the circuit.
        try {
            return aiResilience.execute(() -> service.evaluate(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    public Result<OverallSummaryResult> generateOverallSummary(String systemPrompt, String userMessage,
                                                              String model, double temperature, int maxTokens) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, SUMMARY_REQUIRED_FIELDS, "overallScorePercentage");
        SummaryGenerator service = AiServices.builder(SummaryGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        // Translate a content failure into SchemaValidationException so the offending
        // model output travels to the caller for audit persistence. The catch MUST stay
        // OUTSIDE aiResilience.execute (around it), never inside the supplier: AiResilience's
        // circuit breaker is configured with ignoreExceptions(OutputGuardrailException.class)
        // and must keep seeing the ORIGINAL exception type so content failures don't trip
        // the circuit.
        try {
            return aiResilience.execute(() -> service.generate(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    public Result<TeamInsightResult> generateTeamInsight(String systemPrompt, String userMessage,
                                                        String model, double temperature, int maxTokens) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of("teamThemes"), null);
        TeamInsightGenerator service = AiServices.builder(TeamInsightGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        // Translate a content failure into SchemaValidationException so the offending
        // model output travels to the caller for audit persistence. The catch MUST stay
        // OUTSIDE aiResilience.execute (around it), never inside the supplier: AiResilience's
        // circuit breaker is configured with ignoreExceptions(OutputGuardrailException.class)
        // and must keep seeing the ORIGINAL exception type so content failures don't trip
        // the circuit.
        try {
            return aiResilience.execute(() -> service.generate(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
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
