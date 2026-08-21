package com.bvisionry.aiengine.mock;

import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock model's ROUTING, pinned shape by shape.
 *
 * <p>Regression guard for the sandbox-lane incident: the shift narrative (§6)
 * has its own schema, but {@code defaultFor} had no arm for it, so every mocked
 * generation fell through to the PILLAR default — which carries no
 * {@code kind} and no {@code narrative}, fails {@link
 * com.bvisionry.comparison.web.NarrativeGuardrails} on the first attempt and on
 * every repair, and left "no narrative can be produced locally" looking like a
 * model problem rather than a mock problem.
 *
 * <p>All four shapes are asserted together on purpose: the routing is an
 * ORDERED chain of substring tests over the system prompt, so a rule added or
 * moved can silently steal another shape's prompt. Pin every arm or the order
 * rots.
 */
class MockLangChainChatModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String answerTo(String systemPrompt) {
        return new MockLangChainChatModel()
                .chat(ChatRequest.builder()
                        .messages(SystemMessage.from(systemPrompt), UserMessage.from("data"))
                        .build())
                .aiMessage().text();
    }

    /**
     * The fix. {@code CARRIED_FORWARD} appears only in the shift-narrative
     * prompt (it is one of the five {@code NarrativeKind}s the prompt restates),
     * so it is the discriminator — and the answer must clear the guardrail on
     * its own, including for a DECLINING pillar.
     */
    @Test
    void aShiftNarrativePromptYieldsAGuardrailPassingNarrative() throws Exception {
        String body = answerTo("""
                Classify the shift as RESOLVED, CARRIED_FORWARD, NEW, PERSISTED or FADED,
                then write the narrative and a closing action.""");

        ShiftNarrativeResult result = JSON.readValue(body, ShiftNarrativeResult.class);
        assertThat(result.items()).isNotEmpty();
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.kind()).isNotBlank();
            assertThat(item.text()).isNotBlank();
        });
        // The one that mattered: §6 makes closingAction MANDATORY for a pillar
        // in the decline band. A blank here would make every mocked decline
        // read as a generation failure on a sandbox lane.
        assertThat(result.closingAction()).isNotBlank();
    }

    /** The three shapes that already worked — pinned so the new arm cannot steal them. */
    @Test
    void theOtherThreeShapesStillRouteToTheirOwnSchema() {
        assertThat(answerTo("Return teamThemes and individualCoaching."))
                .contains("\"teamThemes\"");
        assertThat(answerTo("Return overallScorePercentage and summaryNarrative."))
                .contains("\"overallScorePercentage\"");
        // The fallback: a pillar prompt names scorePercentage, which is a
        // SUBSTRING of overallScorePercentage — hence the ordering.
        assertThat(answerTo("Return scorePercentage and evidence."))
                .contains("\"scorePercentage\"")
                .doesNotContain("\"overallScorePercentage\"");
    }

    /** Scripted responses win over every routing arm, drained FIFO. */
    @Test
    void scriptedResponsesAreConsumedBeforeTheDefaults() {
        MockLangChainChatModel model = new MockLangChainChatModel().enqueue("not json");
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("… CARRIED_FORWARD …"), UserMessage.from("data"))
                .build();

        assertThat(model.chat(request).aiMessage().text()).isEqualTo("not json");
        assertThat(model.chat(request).aiMessage().text()).contains("\"kind\"");
    }

    /**
     * The data-grounded arm: a shift-narrative request in the real
     * {@code ShiftNarrativeService.userMessage} shape comes back naming the
     * pillar and quoting the founder's own material — so eleven local pillars
     * yield eleven different narratives, not one canned block. The bare-string
     * fallback above stays pinned by {@link #aShiftNarrativePromptYieldsAGuardrailPassingNarrative}.
     */
    @Test
    void aShiftNarrativeAnswerQuotesTheActualRequest() throws Exception {
        String body = new MockLangChainChatModel()
                .chat(ChatRequest.builder()
                        .messages(SystemMessage.from("… RESOLVED / CARRIED_FORWARD / NEW …"),
                                UserMessage.from("""
                                        PILLAR: HANDLING OBSTACLES
                                        PILLAR DIRECTION: declined

                                        BEFORE — what's working:
                                        - B1: Names the obstacle in plain terms

                                        BEFORE — what can improve:
                                        - B2: Stops at diagnosis without an action step

                                        AFTER — what's working:
                                        - Writes one action with a deadline

                                        ACTIVITY — programme work tagged to this pillar:

                                        TASK: Obstacles & Fears (EXERCISE) — status: done
                                          <submission>
                                          Row 1 · Obstacle: Customs broker wasted an entire month
                                          </submission>
                                        """))
                        .build())
                .aiMessage().text();

        ShiftNarrativeResult result = JSON.readValue(body, ShiftNarrativeResult.class);
        assertThat(result.items()).isNotEmpty();
        String all = result.items().stream().map(ShiftNarrativeResult.Item::text)
                .reduce("", String::concat);
        assertThat(all).contains("HANDLING OBSTACLES")
                .contains("Customs broker wasted an entire month");
        // Direction word "declined" → the mandatory forward-looking close.
        assertThat(result.closingAction()).isNotBlank();
        // Every issued BEFORE id must be declared covered, or the no-vanishing
        // audit flags a coverage gap on every mocked pillar and holds the whole
        // dev lane's rows in DRAFT.
        assertThat(result.items().stream().flatMap(i -> i.covers().stream()))
                .contains("B1", "B2");
    }
}
