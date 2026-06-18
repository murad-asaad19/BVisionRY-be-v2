package com.bvisionry.config.mock;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Static-mock {@link ChatModel} for the {@code mock} Spring profile: returns
 * schema-valid canned JSON instead of calling a live AI provider, so the
 * assessment-evaluation flow works end-to-end with no API key configured.
 *
 * <p>Mirrors the defaults of the test-source {@code com.bvisionry.e2e.FakeChatModel}
 * (which the e2e suite already proves valid) — keep both in sync with the output
 * contracts in {@code OpenRouterChatService}. Discrimination is on a unique key
 * from each schema's output_contract block; order matters because the pillar
 * schema's {@code scorePercentage} is a substring of {@code overallScorePercentage}.
 */
public class MockChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String body = defaultFor(systemText(prompt));
        Generation generation = new Generation(
                new AssistantMessage(body),
                ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build());
    }

    private static String systemText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElse("");
    }

    private static String defaultFor(String systemPrompt) {
        if (systemPrompt.contains("teamThemes")) {
            return TEAM_INSIGHT_DEFAULT;
        }
        if (systemPrompt.contains("overallScorePercentage")) {
            return OVERALL_SUMMARY_DEFAULT;
        }
        return PILLAR_DEFAULT;
    }

    private static final String PILLAR_DEFAULT = """
            {
              "scorePercentage": 72,
              "whatThisScoreMeans": "Solid foundation in this area with clear, addressable growth edges.",
              "whatsWorking": ["Clear ownership of outcomes", "Consistent, structured reflection"],
              "whatCanImprove": ["Tighten feedback loops", "Translate insight into action faster"],
              "whyThisMattersForBusiness": "Faster learning here compounds directly into execution speed.",
              "evidence": [{"qid": "q1", "quote": "I review what worked and what didn't after each milestone."}]
            }
            """;

    private static final String OVERALL_SUMMARY_DEFAULT = """
            {
              "overallScorePercentage": 68,
              "summaryNarrative": "A capable founder building durable habits, with a few focused gaps to close.",
              "strengths": ["Outcome ownership", "Cadence of reflection"],
              "developmentAreas": ["Feedback loop latency", "Longer-horizon planning"],
              "corePattern": "Reflective and action-oriented, but follow-through lags insight.",
              "movingForward": "Pick one improvement, ship it, and measure the impact within two weeks."
            }
            """;

    private static final String TEAM_INSIGHT_DEFAULT = """
            {
              "teamThemes": {
                "commonStrengths": ["Shared sense of purpose"],
                "commonWeaknesses": ["Slow feedback loops"],
                "patterns": ["Reflection without rapid follow-through"],
                "recommendations": ["Shorten retro-to-action cycles."]
              },
              "individualCoaching": [
                {"memberId": "00000000-0000-0000-0000-000000000000", "focusAreas": ["Feedback delivery"], "suggestedActions": ["Practice weekly 1:1s"]}
              ],
              "benchmarking": {
                "teamVsPlatformComparison": "Slightly above platform median on ownership.",
                "outlierPillars": []
              }
            }
            """;
}
