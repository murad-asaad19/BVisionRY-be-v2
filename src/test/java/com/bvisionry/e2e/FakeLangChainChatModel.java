package com.bvisionry.e2e;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

/**
 * E2E-only LangChain4j {@link ChatModel} — the rebuilt-engine equivalent of
 * {@link FakeChatModel}. Same resolution order: a response scripted via
 * {@link FakeChatResponseRegistry#enqueue(String)} is returned first, otherwise a
 * schema-valid default keyed off the system prompt. Lets the full evaluation
 * pipeline run in the e2e suite with no live provider, and lets specs script
 * specific (including adversarial) responses.
 */
public class FakeLangChainChatModel implements ChatModel {

    private final FakeChatResponseRegistry registry;

    public FakeLangChainChatModel(FakeChatResponseRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        String scripted = registry.pollNext();
        String body = scripted != null ? scripted : defaultFor(systemText(chatRequest));
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(body))
                .tokenUsage(new TokenUsage(0, 0))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static String systemText(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                return system.text();
            }
        }
        return "";
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
                "growthEdges": ["Slow feedback loops"],
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
