package com.bvisionry.e2e;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * E2E-only ChatModel that returns canned JSON instead of calling OpenRouter / Anthropic.
 *
 * <p>Resolution order on each call:
 * <ol>
 *   <li>If a response was queued via {@link FakeChatResponseRegistry#enqueue(String)},
 *       pop and return it. Tests use this to script a specific response.</li>
 *   <li>Otherwise, inspect the system prompt to figure out which schema the
 *       caller expects (pillar / overall summary / team insight) and return a
 *       schema-valid default. This keeps tests that don't care about AI output
 *       green without ceremony.</li>
 * </ol>
 *
 * <p>The defaults match the JSON schemas built by
 * {@code OpenRouterChatService.appendPremiumContract / appendFreeTierContract /
 * buildTeamInsightSystemPrompt} — keep them in sync if those schemas evolve.
 */
public class FakeChatModel implements ChatModel {

    private final FakeChatResponseRegistry registry;

    public FakeChatModel(FakeChatResponseRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String scripted = registry.pollNext();
        String body = scripted != null ? scripted : defaultFor(systemText(prompt));

        Generation generation = new Generation(
                new AssistantMessage(body),
                ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatResponse(java.util.List.of(generation),
                ChatResponseMetadata.builder().build());
    }

    private static String systemText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .orElse("");
    }

    /**
     * Discriminates on a unique key from each schema's output_contract block.
     * The pillar schema's {@code scorePercentage} appears in both pillar and
     * (under a different name) overall, so order matters: check the more
     * specific keys first.
     */
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
              "whatThisScoreMeans": "Solid foundation with room to deepen specific practices.",
              "whatsWorking": ["Clear ownership of outcomes", "Consistent retros"],
              "whatCanImprove": ["Tighten feedback loops"],
              "whyThisMattersForBusiness": "Faster learning compounds into faster shipping.",
              "evidence": [{"qid": "q1", "quote": "We retro every two weeks."}]
            }
            """;

    private static final String OVERALL_SUMMARY_DEFAULT = """
            {
              "overallScorePercentage": 68,
              "summaryNarrative": "A capable team building durable habits, with focused gaps to close.",
              "strengths": ["Outcome ownership", "Cadence of reflection"],
              "developmentAreas": ["Feedback loop latency"],
              "corePattern": "Reflection without rapid follow-through.",
              "movingForward": "Pick one improvement, ship it, measure within two weeks."
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
