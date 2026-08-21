package com.bvisionry.aiengine.service;

import com.bvisionry.aiengine.guardrail.AttemptLog;
import com.bvisionry.aiengine.guardrail.SchemaValidationException;
import com.bvisionry.aiengine.guardrail.StructuredOutputGuardrail;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.common.dto.AiUseDetectionResult;
import com.bvisionry.common.dto.CohortGrowthSummaryResult;
import com.bvisionry.common.dto.MemberGrowthSummaryResult;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.bvisionry.common.dto.TeamInsightResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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
                                                         String model, double temperature, int maxTokens,
                                                         AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, PILLAR_REQUIRED_FIELDS, "scorePercentage", attemptLog);
        PillarEvaluator service = AiServices.builder(PillarEvaluator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                // Per-call memory (each service instance serves exactly one call, so
                // nothing leaks across submissions). REQUIRED for the guardrail repair
                // loop to be conversational: OutputGuardrailExecutor builds each retry
                // from chatMemory().messages() + the corrective message — with no
                // memory the retry is sent as ONLY the corrective text, so the model
                // re-answers without the system prompt, the assessment data, or its
                // own prior draft (and can only fabricate).
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
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
                                                              String model, double temperature, int maxTokens,
                                                              AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, SUMMARY_REQUIRED_FIELDS, "overallScorePercentage", attemptLog);
        SummaryGenerator service = AiServices.builder(SummaryGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                // Per-call memory (each service instance serves exactly one call, so
                // nothing leaks across submissions). REQUIRED for the guardrail repair
                // loop to be conversational: OutputGuardrailExecutor builds each retry
                // from chatMemory().messages() + the corrective message — with no
                // memory the retry is sent as ONLY the corrective text, so the model
                // re-answers without the system prompt, the assessment data, or its
                // own prior draft (and can only fabricate).
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
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
                                                        String model, double temperature, int maxTokens,
                                                        AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of("teamThemes"), null, attemptLog);
        TeamInsightGenerator service = AiServices.builder(TeamInsightGenerator.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                // Per-call memory (each service instance serves exactly one call, so
                // nothing leaks across submissions). REQUIRED for the guardrail repair
                // loop to be conversational: OutputGuardrailExecutor builds each retry
                // from chatMemory().messages() + the corrective message — with no
                // memory the retry is sent as ONLY the corrective text, so the model
                // re-answers without the system prompt, the assessment data, or its
                // own prior draft (and can only fabricate).
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
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

    public Result<AiUseDetectionResult> detectAiUse(String systemPrompt, String userMessage,
                                                    String model, double temperature, int maxTokens,
                                                    AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of("answerFindings"), "aiLikelihoodScore", attemptLog);
        AiUseDetector service = AiServices.builder(AiUseDetector.class)
                .chatModel(modelFor(model, temperature, maxTokens))
                // Per-call memory (each service instance serves exactly one call, so
                // nothing leaks across submissions). REQUIRED for the guardrail repair
                // loop to be conversational: OutputGuardrailExecutor builds each retry
                // from chatMemory().messages() + the corrective message — with no
                // memory the retry is sent as ONLY the corrective text, so the model
                // re-answers without the system prompt, the assessment data, or its
                // own prior draft (and can only fabricate).
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
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
            return aiResilience.execute(() -> service.detect(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    /**
     * One pillar's qualitative shift narrative (spec §2). The guardrail's
     * required-field list is deliberately {@code items} only:
     * {@code closingAction} is mandatory for DECLINE pillars alone, which is a
     * config-driven rule the schema guardrail cannot see — the caller
     * ({@code ShiftNarrativeService}) validates it in code and re-asks once with
     * the configured decline-close instruction appended.
     */
    public Result<ShiftNarrativeResult> generateShiftNarrative(String systemPrompt, String userMessage,
                                                               String model, double temperature, int maxTokens,
                                                               AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of("items"), null, attemptLog);
        ShiftNarrativeWriter service = AiServices.builder(ShiftNarrativeWriter.class)
                .chatModel(cachedPromptModelFor(model, temperature, maxTokens))
                // Per-call memory — see evaluatePillar for why the repair loop needs it.
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        try {
            return aiResilience.execute(() -> service.write(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    /**
     * One founder's overall growth summary (spec §3), written from their
     * approved per-pillar narratives.
     *
     * <p>No required field, deliberately: since V193 the shipped contract is
     * {@code items} (the classified breakdown) while an installation whose
     * admins customised the template still asks for {@code summary}, and this
     * guardrail can only demand ALL of a list. Requiring either one would hard-
     * fail the other's install. The JSON-validity checks still run here;
     * "did a usable summary come back" is asserted once, in
     * {@code MemberGrowthSummaryService.generate}, where the two shapes are
     * already reconciled.
     */
    public Result<MemberGrowthSummaryResult> generateMemberGrowthSummary(String systemPrompt, String userMessage,
                                                                         String model, double temperature,
                                                                         int maxTokens, AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of(), null, attemptLog);
        MemberGrowthSummaryWriter service = AiServices.builder(MemberGrowthSummaryWriter.class)
                .chatModel(cachedPromptModelFor(model, temperature, maxTokens))
                // Per-call memory — see evaluatePillar for why the repair loop needs it.
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        try {
            return aiResilience.execute(() -> service.write(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    /**
     * The cohort-level growth report (spec §4). One required field —
     * {@code overview} — for the same reason {@link #generateTeamInsight} names
     * only {@code teamThemes}: the head of the contract is what a repair retry
     * has to be told is missing.
     */
    public Result<CohortGrowthSummaryResult> generateCohortGrowthSummary(String systemPrompt, String userMessage,
                                                                          String model, double temperature,
                                                                          int maxTokens, AttemptLog attemptLog) {
        StructuredOutputGuardrail guardrail =
                new StructuredOutputGuardrail(MAPPER, List.of("overview"), null, attemptLog);
        CohortGrowthSummaryWriter service = AiServices.builder(CohortGrowthSummaryWriter.class)
                .chatModel(cachedPromptModelFor(model, temperature, maxTokens))
                // Per-call memory — see evaluatePillar for why the repair loop needs it.
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .systemMessageProvider(memoryId -> systemPrompt)
                .outputGuardrails(guardrail)
                .outputGuardrailsConfig(retryConfig())
                .build();
        try {
            return aiResilience.execute(() -> service.write(userMessage));
        } catch (OutputGuardrailException ge) {
            throw new SchemaValidationException(ge.getMessage(), guardrail.lastResponseText(), ge);
        }
    }

    private ChatModel modelFor(String model, double temperature, int maxTokens) {
        return modelProvider.modelFor(model, temperature, maxTokens, false);
    }

    /**
     * The transport for the three review-gate writers (spec §2/§3/§4), and the only
     * calls that ask the provider to cache the prompt.
     *
     * <p>They are the ones whose input genuinely repeats: a reviewer who dislikes a
     * draft presses Regenerate, which re-sends a byte-identical prompt seconds later,
     * and unlike {@link #evaluatePillar} these have no {@code ai_evaluation_cache} in
     * front of them to absorb the repeat. The other calls deliberately stay off — a
     * cache write costs 1.25×, so paying it on a prompt that is never seen twice is
     * a straight loss.
     */
    private ChatModel cachedPromptModelFor(String model, double temperature, int maxTokens) {
        return modelProvider.modelFor(model, temperature, maxTokens, true);
    }

    private OutputGuardrailsConfig retryConfig() {
        return OutputGuardrailsConfig.builder()
                .maxRetries(repairRetries)
                .build();
    }
}
