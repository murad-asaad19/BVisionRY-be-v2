package com.bvisionry.aiengine.mock;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LangChain4j {@link ChatModel} that returns canned, schema-valid JSON instead
 * of calling a live provider — the LangChain4j-seam equivalent of the former
 * Spring-AI {@code MockChatModel}. Used under the {@code mock} (and, scripted,
 * {@code e2e}) profiles so the full evaluation pipeline runs with no API key.
 *
 * <p>By default it discriminates on the system prompt (each schema's contract has
 * a unique field name) and returns a valid default. Tests can {@link #enqueue}
 * specific raw responses — including deliberately malformed / out-of-range output
 * — to exercise the guardrail repair loop and fail-loud paths. Scripted responses
 * are consumed FIFO; once drained it falls back to the canned defaults.
 */
public class MockLangChainChatModel implements ChatModel {

    private final Queue<String> scripted = new ConcurrentLinkedQueue<>();

    /** Queue a raw model response to be returned by the next call(s), FIFO. */
    public MockLangChainChatModel enqueue(String rawResponse) {
        scripted.add(rawResponse);
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        String body = scripted.poll();
        if (body == null) {
            body = defaultFor(systemText(chatRequest));
        }
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
        // Order matters: the pillar schema's scorePercentage is a substring of
        // overallScorePercentage, so check the more specific keys first.
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
