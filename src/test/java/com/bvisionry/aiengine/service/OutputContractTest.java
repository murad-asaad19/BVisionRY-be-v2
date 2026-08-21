package com.bvisionry.aiengine.service;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.guardrail.AttemptLog;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.aiengine.resilience.AiResilience;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WHO tells the model what JSON to return.
 *
 * <p>V194 deleted the response contract from the editable prompt text on the
 * grounds that LangChain4j derives it from the AI service's typed return on
 * BOTH transport paths. That is the load-bearing claim behind an admin being
 * unable to break parsing with a prompt edit — and it was never asserted. This
 * test asserts it, on both branches, by capturing the {@link ChatRequest} the
 * engine actually builds.
 *
 * <p>Both branches are live in production simultaneously: as of 2026-08-19
 * staging runs {@code anthropic/claude-sonnet-4}, which OpenRouter reports with
 * NO {@code structured_outputs}, while local dev runs {@code claude-sonnet-4.6},
 * which has it. So the universal path is not a theoretical fallback — it is
 * what staging is on, and it is the branch that must not silently lose the shape.
 */
class OutputContractTest {

    /** A {@link ChatModel} that records the request and answers with valid narrative JSON. */
    private static final class CapturingModel implements ChatModel {

        private final Set<Capability> capabilities;
        private final AtomicReference<ChatRequest> seen = new AtomicReference<>();

        CapturingModel(Set<Capability> capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return capabilities;
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            seen.compareAndSet(null, chatRequest);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("""
                            {"items":[{"kind":"NEW","text":"A written decision log appears."}],
                             "closingAction":"Keep the log."}
                            """))
                    .tokenUsage(new TokenUsage(0, 0))
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        ChatRequest request() {
            return seen.get();
        }

        /** Every user-side message, concatenated — where format instructions land. */
        String userText() {
            StringBuilder sb = new StringBuilder();
            for (ChatMessage m : request().messages()) {
                if (m instanceof UserMessage u) {
                    sb.append(u.singleText()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static void generateWith(CapturingModel model) {
        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(
                mock(AIConfigService.class), mock(ModelCapabilityRegistry.class)) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens,
                                      boolean cachePrompt) {
                return model;
            }
        };
        AiResilience resilience =
                new AiResilience(new SimpleMeterRegistry(), 50f, 80f, 120, 30, 20, 8, 32, 3000);
        new AiEvaluationEngine(provider, resilience, 2).generateShiftNarrative(
                // A system prompt with NO mention of JSON — exactly what V194 left behind.
                "You write a short qualitative breakdown. Classify as RESOLVED or CARRIED_FORWARD.",
                "PILLAR: Vision\nPILLAR DIRECTION: improved\n",
                "anthropic/claude-sonnet-4", 0.3, 2000, new AttemptLog());
    }

    /**
     * The branch STAGING is on. No native schema support, so the shape has to
     * travel in the message — otherwise V194 left the model with no contract at
     * all and every generation would ride the repair loop into a failure.
     */
    @Test
    void withoutNativeSchemaSupport_theContractIsInjectedIntoTheMessage() {
        CapturingModel model = new CapturingModel(Set.of());

        generateWith(model);

        String sent = model.userText();
        // Every field the parser deserialises has to be named to the model.
        assertThat(sent).contains("items").contains("kind").contains("text")
                .contains("closingAction");
        // ...as an explicit JSON instruction, not incidental prose.
        assertThat(sent).containsIgnoringCase("json");
        // And the engine did NOT ask the provider to enforce a schema it cannot.
        assertThat(model.request().parameters().responseFormat()).isNull();
    }

    /**
     * The branch LOCAL DEV is on (claude-sonnet-4.6). The schema is handed to
     * the provider to enforce, derived from {@code ShiftNarrativeResult} — so a
     * prompt edit still cannot change the shape that comes back.
     */
    @Test
    void withNativeSchemaSupport_theSchemaIsSentAsResponseFormat() {
        CapturingModel model = new CapturingModel(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        generateWith(model);

        var responseFormat = model.request().parameters().responseFormat();
        assertThat(responseFormat).isNotNull();
        assertThat(responseFormat.jsonSchema()).isNotNull();
        // Derived from the typed return, so the contract tracks the record.
        assertThat(responseFormat.jsonSchema().toString())
                .contains("items").contains("closingAction");
    }
}
