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
        registry.recordUserMessage(userText(chatRequest));
        String scripted = registry.pollNext();
        String body = scripted != null ? scripted : defaultFor(systemText(chatRequest));
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(body))
                .tokenUsage(new TokenUsage(0, 0))
                .finishReason(FinishReason.STOP)
                .build();
    }

    /** The FIRST user message — the data the call was built on, not a repair re-ask. */
    private static String userText(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof dev.langchain4j.data.message.UserMessage user) {
                return user.singleText();
            }
        }
        return "";
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
        // The member growth summary (§3). Checked BEFORE the narrative arm:
        // since V193 both prompts carry the five kinds, so "overall growth
        // breakdown" — which appears only here — is what tells them apart.
        if (systemPrompt.contains("overall growth breakdown")) {
            return MEMBER_GROWTH_BREAKDOWN_DEFAULT;
        }
        // Its pre-V193 contract: the only prompt that names {"summary".
        if (systemPrompt.contains("{\"summary\"")) {
            return MEMBER_GROWTH_SUMMARY_DEFAULT;
        }
        // The cohort growth summary (§4). Routed on the INSTRUCTION, not the
        // schema: V194 took the JSON envelope out of the prompt (LangChain4j
        // derives it from CohortGrowthSummaryResult), so {"overview" is no
        // longer there to match on. "COHORT-WIDE" is that prompt's whole job
        // and appears in no other.
        if (systemPrompt.contains("COHORT-WIDE")) {
            return COHORT_GROWTH_SUMMARY_DEFAULT;
        }
        if (systemPrompt.contains("closingAction")) {
            return SHIFT_NARRATIVE_DEFAULT;
        }
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

    /**
     * Shift-narrative default (redesign spec §6). Carries a non-empty
     * {@code closingAction} so it also satisfies the decline guardrail — a spec
     * that wants to exercise the retry path scripts the failing draft itself.
     */
    private static final String SHIFT_NARRATIVE_DEFAULT = """
            {
              "items": [
                {"kind": "CARRIED_FORWARD",
                 "text": "The habit named in the first assessment is still here, and it has sharpened."},
                {"kind": "NEW",
                 "text": "A weekly review appears in the later text with no equivalent before it."}
              ],
              "closingAction": "Name the next constraint before it becomes the bottleneck."
            }
            """;

    /** The member-level growth breakdown (V193) — the five kinds, merged across pillars. */
    private static final String MEMBER_GROWTH_BREAKDOWN_DEFAULT = """
            {
              "items": [
                {"kind": "CARRIED_FORWARD",
                 "text": "The habits named at intake have taken hold, and across most pillars \
            decisions now get written down and revisited on a cadence."},
                {"kind": "PERSISTED",
                 "text": "Turning that steadiness outward is still the open edge, and the \
            follow-through thins in the same places it did before."},
                {"kind": "RESOLVED",
                 "text": "The hesitation about asking for help no longer appears anywhere in \
            the later readings."}
              ]
            }
            """;

    /** The member-level growth summary's pre-V193 single-paragraph contract. */
    private static final String MEMBER_GROWTH_SUMMARY_DEFAULT = """
            {
              "summary": "The habits named at intake have taken hold, and the later work reads \
            steadier than the first: decisions get written down and revisited on a cadence. \
            What is still open is turning that steadiness outward, where the follow-through \
            thins out."
            }
            """;

    /** The cohort-level growth report (redesign spec §4). */
    private static final String COHORT_GROWTH_SUMMARY_DEFAULT = """
            {
              "overview": "The cohort moved together on the habits the programme drilled, \
            and split on the ones it only talked about.",
              "sharedWins": ["Written decisions are now the norm across most of the group"],
              "sharedRisks": ["Follow-through thins out once the work leaves the founder's desk"],
              "recommendations": ["Run one session on handing work over, using the group's own examples"]
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
