package com.bvisionry.aiengine.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure tests for the model-agnostic robustness core: malformed, incomplete, and
 * out-of-range model output must trigger a reprompt (the repair signal), while
 * valid output — even wrapped in prose or fences — passes and is normalized to
 * clean JSON. No network, no Spring.
 */
class StructuredOutputGuardrailTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredOutputGuardrail scoreGuard =
            new StructuredOutputGuardrail(mapper, List.of(), "scorePercentage");

    private OutputGuardrailResult validate(StructuredOutputGuardrail g, String text) {
        return g.validate(AiMessage.from(text));
    }

    @Test
    void cleanJson_passes() {
        OutputGuardrailResult r = validate(scoreGuard, "{\"scorePercentage\": 75}");
        assertThat(r.isReprompt()).isFalse();
        assertThat(r.successfulText()).contains("\"scorePercentage\"");
    }

    @Test
    void jsonWrappedInProseWithTrailingBraces_isExtractedAndPasses() {
        // The old lastIndexOf('}') heuristic would mis-extract here (F1).
        String text = "Here is the result: {\"scorePercentage\": 80} Note: scores are {0-100}.";
        OutputGuardrailResult r = validate(scoreGuard, text);
        assertThat(r.isReprompt()).isFalse();
        assertThat(r.successfulText().trim()).isEqualTo("{\"scorePercentage\": 80}");
    }

    @Test
    void markdownFencedJson_passes() {
        OutputGuardrailResult r = validate(scoreGuard, "```json\n{\"scorePercentage\": 60}\n```");
        assertThat(r.isReprompt()).isFalse();
    }

    @Test
    void refusalProseWithNoJson_reprompts() {
        assertThat(validate(scoreGuard, "I'm sorry, I can't help with that.").isReprompt()).isTrue();
    }

    @Test
    void truncatedJson_reprompts() {
        // Unbalanced braces (max_tokens cutoff) — F3.
        assertThat(validate(scoreGuard, "{\"scorePercentage\": 75, \"note\": \"unterminated").isReprompt()).isTrue();
    }

    @Test
    void malformedJson_reprompts() {
        assertThat(validate(scoreGuard, "{\"scorePercentage\": }").isReprompt()).isTrue();
    }

    @Test
    void missingScoreField_reprompts() {
        // Primitive-default trap (F4): no score present must repair, not silently become 0.
        assertThat(validate(scoreGuard, "{\"whatThisScoreMeans\": \"x\"}").isReprompt()).isTrue();
    }

    @Test
    void outOfRangeScore_reprompts() {
        // Hallucinated value (F6).
        assertThat(validate(scoreGuard, "{\"scorePercentage\": 150}").isReprompt()).isTrue();
    }

    @Test
    void nonIntegerScore_reprompts() {
        assertThat(validate(scoreGuard, "{\"scorePercentage\": \"high\"}").isReprompt()).isTrue();
    }

    @Test
    void requiredFieldGuard_missingField_reprompts() {
        StructuredOutputGuardrail teamGuard = new StructuredOutputGuardrail(mapper, List.of("teamThemes"), null);
        assertThat(validate(teamGuard, "{\"benchmarking\": {}}").isReprompt()).isTrue();
        assertThat(validate(teamGuard, "{\"teamThemes\": {\"patterns\": []}}").isReprompt()).isFalse();
    }

    /**
     * H3: a score-only pillar response (the model dropped all narrative) must reprompt,
     * not pass on the strength of a valid score. Mirrors the production pillar guardrail
     * wired in {@code AiEvaluationEngine}.
     */
    private final StructuredOutputGuardrail pillarGuard = new StructuredOutputGuardrail(
            mapper, List.of("whatThisScoreMeans", "whatsWorking", "whatCanImprove"), "scorePercentage");

    @Test
    void pillarGuard_scoreOnly_droppingNarrative_reprompts() {
        assertThat(validate(pillarGuard, "{\"scorePercentage\": 72}").isReprompt()).isTrue();
    }

    @Test
    void pillarGuard_validScore_butMissingOneNarrativeField_reprompts() {
        String missingImprove =
                "{\"scorePercentage\": 72, \"whatThisScoreMeans\": \"ok\", \"whatsWorking\": [\"a\"]}";
        assertThat(validate(pillarGuard, missingImprove).isReprompt()).isTrue();
    }

    @Test
    void pillarGuard_completeResponse_passes() {
        String complete = "{\"scorePercentage\": 72, \"whatThisScoreMeans\": \"ok\","
                + " \"whatsWorking\": [\"a\"], \"whatCanImprove\": [\"b\"]}";
        assertThat(validate(pillarGuard, complete).isReprompt()).isFalse();
    }

    /**
     * The guardrail retains the raw offending text on a failing validate so the caller
     * can persist real evidence when the retry budget is exhausted (see
     * {@code AiEvaluationEngine} → {@code SchemaValidationException}).
     */
    @Test
    void retainsLastResponseText_onFailingValidate() {
        String bad = "I'm sorry, I can't help with that.";
        assertThat(validate(scoreGuard, bad).isReprompt()).isTrue();
        assertThat(scoreGuard.lastResponseText()).isEqualTo(bad);
    }

    /**
     * A truncated overall summary carrying only a bare score must reprompt — the production
     * summary guardrail requires the narrative fields, so a missing corePattern can't render
     * as a clean EVALUATED with blank sections. Mirrors the guardrail wired in
     * {@code AiEvaluationEngine.generateOverallSummary}.
     */
    private final StructuredOutputGuardrail summaryGuard = new StructuredOutputGuardrail(
            mapper, List.of("summaryNarrative", "strengths", "developmentAreas", "corePattern", "movingForward"),
            "overallScorePercentage");

    @Test
    void summaryGuard_missingCorePattern_reprompts() {
        String missingCorePattern = "{\"overallScorePercentage\": 68,"
                + " \"summaryNarrative\": \"ok\", \"strengths\": [\"a\"],"
                + " \"developmentAreas\": [\"b\"], \"movingForward\": \"go\"}";
        assertThat(validate(summaryGuard, missingCorePattern).isReprompt()).isTrue();
    }

    @Test
    void summaryGuard_completeResponse_passes() {
        String complete = "{\"overallScorePercentage\": 68,"
                + " \"summaryNarrative\": \"ok\", \"strengths\": [\"a\"],"
                + " \"developmentAreas\": [\"b\"], \"corePattern\": \"p\", \"movingForward\": \"go\"}";
        assertThat(validate(summaryGuard, complete).isReprompt()).isFalse();
    }
}
