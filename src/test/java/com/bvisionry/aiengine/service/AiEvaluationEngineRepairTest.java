package com.bvisionry.aiengine.service;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.guardrail.AttemptLog;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.aiengine.guardrail.SchemaValidationException;
import com.bvisionry.common.dto.PillarEvaluationResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves the end-to-end repair loop with the real LangChain4j AiServices machinery
 * and a scripted mock model: a first malformed response must trigger a reprompt and
 * the engine must recover to a valid typed result; with no repair budget the same
 * bad response must surface as a hard failure. This is the load-bearing guarantee
 * of the model-agnostic design — uncertain output is handled, not silently accepted.
 */
class AiEvaluationEngineRepairTest {

    private AiEvaluationEngine engineWith(MockLangChainChatModel model, int repairRetries) {
        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(
                mock(AIConfigService.class), mock(ModelCapabilityRegistry.class)) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                return model;
            }
        };
        // Production-like resilience defaults (thresholds/windows/bulkhead).
        AiResilience resilience = new AiResilience(new SimpleMeterRegistry(), 50f, 80f, 120, 30, 20, 8, 32, 3000);
        return new AiEvaluationEngine(provider, resilience, repairRetries);
    }

    @Test
    void malformedFirstResponse_isRepromptedAndRecovers() {
        // First call: prose with no JSON → guardrail reprompts. Second call: queue
        // drained → canned schema-valid pillar JSON (score 72).
        MockLangChainChatModel model = new MockLangChainChatModel()
                .enqueue("I'm sorry, I cannot produce a score for this.");

        AiEvaluationEngine engine = engineWith(model, 2);

        Result<PillarEvaluationResult> result = engine.evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024, new AttemptLog());

        assertThat(result.content()).isNotNull();
        assertThat(result.content().scorePercentage()).isEqualTo(72);
    }

    /** Mock model that additionally records the messages of every request it receives. */
    private static class CapturingMockModel extends MockLangChainChatModel {
        final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            requests.add(List.copyOf(chatRequest.messages()));
            return super.chat(chatRequest);
        }
    }

    /**
     * Production incident regression: the guardrail retry must be CONVERSATIONAL.
     * OutputGuardrailExecutor builds each retry from chatMemory().messages() plus the
     * corrective message — without a chat memory on the AiServices build, the retry
     * was sent as ONLY the corrective text (observed live: a 1-message request with
     * no system prompt and no assessment data), so the model could not re-evaluate,
     * only fabricate. Pins that the retry carries the system prompt and the original
     * user message, with the corrective message appended last.
     */
    @Test
    void repromptRetry_carriesSystemPromptAndOriginalUserMessage() {
        CapturingMockModel model = new CapturingMockModel();
        // The production draft shape: parseable JSON with a null score and missing
        // narratives — triggers the batched field-violation reprompt.
        model.enqueue("{\"scorePercentage\": null, \"evidence\": []}");

        AiEvaluationEngine engine = engineWith(model, 2);

        Result<PillarEvaluationResult> result = engine.evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024, new AttemptLog());
        assertThat(result.content()).isNotNull();

        assertThat(model.requests).hasSize(2);
        List<ChatMessage> retry = model.requests.get(1);
        // The retry must NOT be a lone corrective message: it needs the system
        // prompt and the original user message so the model can actually re-evaluate.
        assertThat(retry.size()).isGreaterThanOrEqualTo(3);
        assertThat(retry.stream().anyMatch(m -> m instanceof SystemMessage sm
                && sm.text().contains("You are an evaluator"))).isTrue();
        assertThat(retry.stream().anyMatch(m -> m instanceof UserMessage um
                && um.singleText().contains("assessment data"))).isTrue();
        // The corrective message arrives last and restates the contract.
        ChatMessage last = retry.get(retry.size() - 1);
        assertThat(last).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) last).singleText()).contains("scorePercentage");
    }

    /**
     * The repair round-trips happen inside LangChain4j, so without an explicit capture
     * the audit trail records only a call's FINAL state. Pins that the AttemptLog
     * collects every draft with its outcome and the corrective message that followed,
     * so ai_call_logs can show the whole history.
     */
    @Test
    void attemptLog_capturesEveryDraftAndCorrection() {
        MockLangChainChatModel model = new MockLangChainChatModel()
                .enqueue("{\"scorePercentage\": null, \"evidence\": []}");
        AttemptLog attemptLog = new AttemptLog();

        engineWith(model, 2).evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024, attemptLog);

        // Draft 1 rejected, draft 2 (canned valid default) accepted.
        assertThat(attemptLog.count()).isEqualTo(2);
        assertThat(attemptLog.hasRepairs()).isTrue();

        String json = attemptLog.toJson();
        assertThat(json)
                .contains("\"attempt\":1")
                .contains("\"outcome\":\"REPROMPTED\"")
                .contains("\"attempt\":2")
                .contains("\"outcome\":\"ACCEPTED\"")
                // The rejected draft and the reason/correction are all preserved.
                .contains("scorePercentage")
                .contains("\"reason\"")
                .contains("\"correction\"");
    }

    @Test
    void attemptLog_cleanFirstPass_recordsSingleAcceptedAttempt() {
        AttemptLog attemptLog = new AttemptLog();

        engineWith(new MockLangChainChatModel(), 2).evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024, attemptLog);

        assertThat(attemptLog.count()).isEqualTo(1);
        // No repair → the existing prompt/response columns already tell the story,
        // so AICallLogService skips persisting the history for this shape.
        assertThat(attemptLog.hasRepairs()).isFalse();
    }

    @Test
    void noRepairBudget_malformedResponse_failsHard_withRawOutputAttached() {
        MockLangChainChatModel model = new MockLangChainChatModel()
                .enqueue("I'm sorry, I cannot produce a score for this.");

        AiEvaluationEngine engine = engineWith(model, 0);

        // The content failure surfaces as SchemaValidationException (not the raw
        // OutputGuardrailException) so the offending model output travels with it for
        // audit persistence; the circuit breaker still ignores the ORIGINAL type.
        assertThatThrownBy(() -> engine.evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024, new AttemptLog()))
                .isInstanceOf(SchemaValidationException.class)
                .satisfies(ex -> assertThat(((SchemaValidationException) ex).getRawModelOutput())
                        .isEqualTo("I'm sorry, I cannot produce a score for this."));
    }
}
