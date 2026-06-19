package com.bvisionry.aiengine.eval;

import com.bvisionry.aiengine.service.AiEvaluationEngine;
import com.bvisionry.common.dto.PillarEvaluationResult;
import dev.langchain4j.service.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Benchmarks a configured model against a golden set by running each case through
 * the real engine path (structured output + guardrail repair), then scoring the
 * output for schema validity, in-band accuracy, and latency.
 *
 * <p>This is the operational backbone of model-agnosticism: before trusting a
 * newly-configured model, run it through here and gate the switch on the report.
 * It deliberately reuses {@link AiEvaluationEngine#evaluatePillar} so the
 * benchmark exercises exactly the code path production uses — guardrails,
 * resilience, the lot — not a parallel shortcut. Runs deterministically against
 * the mock/fake model in CI, and against a real model when pointed at one.
 */
@Component
@Slf4j
public class ModelEvalHarness {

    private final AiEvaluationEngine engine;

    public ModelEvalHarness(AiEvaluationEngine engine) {
        this.engine = engine;
    }

    /** Minimal, model-agnostic scoring prompt with the pillar output contract. */
    private static final String SYSTEM_PROMPT = """
            You are an assessment evaluator. Read the assessment data and score it from 0 to 100.
            <output_contract>
            Return ONLY a JSON object matching:
            {"scorePercentage": integer in [0,100], "whatThisScoreMeans": string, "whatsWorking": [string], "whatCanImprove": [string], "whyThisMattersForBusiness": string, "evidence": [{"qid": string, "quote": string}]}
            No prose, no markdown fences.
            </output_contract>
            """;

    public ModelEvalReport run(String model, double temperature, int maxTokens, List<GoldenCase> cases) {
        List<ModelEvalReport.CaseResult> results = new ArrayList<>();
        long totalLatency = 0;
        int schemaValid = 0;
        int inBand = 0;

        for (GoldenCase c : cases) {
            long start = System.currentTimeMillis();
            ModelEvalReport.CaseResult caseResult;
            try {
                String userMessage = "<rubric>" + nullSafe(c.rubric()) + "</rubric>\n" + nullSafe(c.assessmentXml());
                Result<PillarEvaluationResult> result =
                        engine.evaluatePillar(SYSTEM_PROMPT, userMessage, model, temperature, maxTokens);
                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;

                int score = result.content().scorePercentage();
                boolean band = score >= c.expectedMin() && score <= c.expectedMax();
                schemaValid++;
                if (band) {
                    inBand++;
                }
                caseResult = new ModelEvalReport.CaseResult(c.name(), true, score, band, latency,
                        band ? "ok" : "score " + score + " outside [" + c.expectedMin() + "," + c.expectedMax() + "]");
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;
                caseResult = new ModelEvalReport.CaseResult(c.name(), false, null, false, latency,
                        "failed: " + e.getMessage());
            }
            results.add(caseResult);
        }

        int total = cases.size();
        double passRate = total == 0 ? 0.0 : (double) inBand / total;
        long avgLatency = total == 0 ? 0L : totalLatency / total;
        ModelEvalReport report = new ModelEvalReport(model, total, schemaValid, inBand, passRate, avgLatency, results);
        log.info("Model-eval: {}", report.summary());
        return report;
    }

    /**
     * A small built-in golden set. Bands are intentionally wide because the point
     * is to catch a model that's badly miscalibrated or can't produce valid output
     * — not to enforce a precise score. Externalize/extend per deployment.
     */
    public static List<GoldenCase> defaultGoldenSet() {
        return List.of(
                new GoldenCase(
                        "strong-specific-ownership",
                        "Reward clear ownership of outcomes and concrete, specific reflection.",
                        "<assessment_data><response qid=\"q1\"><answer>I own every outcome end to end and run a structured retro after each milestone, turning two concrete learnings into action within the week.</answer></response></assessment_data>",
                        45, 100),
                new GoldenCase(
                        "weak-vague-answer",
                        "Penalize vague, non-specific, low-effort answers.",
                        "<assessment_data><response qid=\"q1\"><answer>idk, we just kind of wing it i guess</answer></response></assessment_data>",
                        0, 70));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
