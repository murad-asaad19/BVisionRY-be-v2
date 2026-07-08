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
                "growthEdges": ["Slow feedback loops"],
                "patterns": ["Reflection without rapid follow-through"],
                "recommendations": ["Shorten retro-to-action cycles."]
              },
              "individualCoaching": [
                {"memberId": "00000000-0000-0000-0000-000000000000", "focusAreas": ["Handling Obstacles (20%) — lowest score in the entire team; obstacle is defined as a result rather than a root cause; emotional naming is rated Very Difficult; reframe is absent; no action step was produced", "Energy and Motivation (22%) — second lowest score in the team; no named pain driver, no vivid pleasure driver; motivation appears obligation-based with no described future state", "Listening Mindset (28%) — lowest listening score in the team; physiological observation layer is entirely undeveloped", "Focus and Flow (29%) — second lowest focus score; phone and messaging apps at very high distraction levels; zero shield tools in place", "Discipline (29%) — third lowest discipline score; cognitive fuel domain is inactive; physical movement is absent"], "suggestedActions": ["Start the obstacle navigation sequence with the single most pressing current challenge: write one sentence describing the obstacle using only observable language, name one emotion, then write one action with a deadline.", "Develop a written pain driver and a written pleasure driver describing what the business looks and feels like in two years."]},
                {"memberId": "00000000-0000-0000-0000-000000000001", "focusAreas": ["Curiosity (13%) — lowest curiosity score in the team; all three questions accepted the statistic as true rather than questioning the claim, its source, or its methodology", "Vision Mindset (25%) — second lowest vision score; no specific person served, no legacy impact; vision reads as a product category", "Handling Obstacles (45%) — obstacle definition is an internal state rather than a specific observable problem; reframe is stated but not articulated", "Focus and Flow (62%) — no focus tools in place; internal thoughts and anxiety are elevated; focus endurance is capped"], "suggestedActions": ["Practice the curiosity loop: for every claim, write down its source, its definition, and one way it could be wrong before accepting it.", "Rewrite the vision around a specific person served and the legacy impact on them."]},
                {"memberId": "00000000-0000-0000-0000-000000000002", "focusAreas": ["Energy and Motivation (54%) — pain driver is present but general; the self-regulation strategy exists but the vision pulling forward is not yet vivid", "Discipline (68%) — habits are forming but restorative practices are inconsistent", "Growth Mindset (74%) — receptive to feedback; occasionally defends rather than explores"], "suggestedActions": ["Make the pleasure driver concrete: describe the specific future day in the business that is worth the effort.", "Protect two keystone habits for 60 days before adding anything new."]}
              ],
              "benchmarking": {
                "teamVsPlatformComparison": "Slightly above platform median on ownership; notably below on the execution stack (Discipline, Focus, Handling Obstacles).",
                "outlierPillars": ["Curiosity — team sits well below platform average", "Handling Obstacles — team sits well below platform average"]
              }
            }
            """;
}
