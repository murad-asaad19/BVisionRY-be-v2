package com.bvisionry.aiengine.service;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the team-insight aggregate actually reaches the model as the user message.
 * If the aggregate is missing/empty in the request, the model has no team data to
 * ground on and confabulates a generic report — which is exactly the production
 * symptom. This isolates "wiring lost the data" from "provider ignored the data".
 */
class TeamInsightUserMessageCaptureTest {

    /** Records the last request so the test can inspect what was actually sent. */
    private static final class CapturingChatModel implements ChatModel {
        volatile ChatRequest last;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            this.last = chatRequest;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(VALID_TEAM_INSIGHT_JSON))
                    .tokenUsage(new TokenUsage(0, 0))
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    private AiEvaluationEngine engineWith(ChatModel model) {
        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(
                mock(AIConfigService.class), mock(ModelCapabilityRegistry.class)) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                return model;
            }
        };
        AiResilience resilience = new AiResilience(new SimpleMeterRegistry(), 50f, 80f, 120, 30, 20, 8, 32, 3000);
        return new AiEvaluationEngine(provider, resilience, 2);
    }

    @Test
    void aggregate_reachesModel_asUserMessage() {
        CapturingChatModel model = new CapturingChatModel();
        AiEvaluationEngine engine = engineWith(model);

        String aggregate = "AGG_MARKER_7f3\n"
                + "Team size: 9 members evaluated.\n\n"
                + "Pillar: Handling Obstacles\n  Average score: 41.67%\n"
                + "  Maturity distribution: {Emerging=5, Formative=4}\n\n"
                + "Individual member scores:\n  Member 1: Handling Obstacles=30% Vision Mindset=58%\n";

        engine.generateTeamInsight(
                "<output_contract> teamThemes ... </output_contract>",
                aggregate,
                "anthropic/claude-sonnet-4", 0.6, 2048);

        assertThat(model.last).as("model was called").isNotNull();

        String userText = model.last.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(TeamInsightUserMessageCaptureTest::text)
                .findFirst()
                .orElse("");

        assertThat(userText)
                .as("the team aggregate must reach the model as the user message")
                .contains("AGG_MARKER_7f3")
                .contains("Handling Obstacles")
                .contains("Member 1: Handling Obstacles=30%")
                .contains("{Emerging=5, Formative=4}");
    }

    private static String text(ChatMessage message) {
        return ((UserMessage) message).singleText();
    }

    private static final String VALID_TEAM_INSIGHT_JSON = """
            {
              "teamThemes": {
                "commonStrengths": ["Shared purpose"],
                "growthEdges": ["Slow feedback"],
                "patterns": ["Reflection without follow-through"],
                "recommendations": ["Shorten cycles"]
              },
              "individualCoaching": [
                {"memberId": "Member 1", "focusAreas": ["Handling Obstacles (30%) — vague"], "suggestedActions": ["Write one obstacle sentence"]}
              ],
              "benchmarking": {
                "teamVsPlatformComparison": "Below platform on execution.",
                "outlierPillars": ["Handling Obstacles"]
              }
            }
            """;
}
