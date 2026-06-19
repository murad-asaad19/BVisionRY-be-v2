package com.bvisionry.aiengine.service;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.common.dto.PillarEvaluationResult;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
        return new AiEvaluationEngine(provider, AiResilience.withDefaults(new SimpleMeterRegistry()), repairRetries);
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
                "anthropic/claude-sonnet-4", 0.3, 1024);

        assertThat(result.content()).isNotNull();
        assertThat(result.content().scorePercentage()).isEqualTo(72);
    }

    @Test
    void noRepairBudget_malformedResponse_failsHard() {
        MockLangChainChatModel model = new MockLangChainChatModel()
                .enqueue("I'm sorry, I cannot produce a score for this.");

        AiEvaluationEngine engine = engineWith(model, 0);

        assertThatThrownBy(() -> engine.evaluatePillar(
                "You are an evaluator. Return scorePercentage.",
                "assessment data",
                "anthropic/claude-sonnet-4", 0.3, 1024))
                .isInstanceOf(OutputGuardrailException.class);
    }
}
