package com.bvisionry.aiengine.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The model-agnostic robustness core: a declarative output guardrail that
 * validates a model's response and, on failure, triggers a <em>reprompt</em> so
 * LangChain4j re-asks the model with a corrective instruction (up to the
 * configured retry budget) instead of silently accepting garbage.
 *
 * <p>Checks:
 * <ol>
 *   <li>a single balanced JSON object is present ({@link JsonExtraction}) — else reprompt (F1–F3);</li>
 *   <li>it parses as a JSON object — else reprompt;</li>
 *   <li>all {@code requiredFields} are present and non-null (F4), and — if a
 *       {@code scoreField} is given — it is an integer in [0,100] (F6). These field
 *       checks are accumulated, NOT short-circuited: one reprompt names EVERY
 *       violation and restates the full field contract. A sequenced reprompt
 *       ("fix the narratives" … "now fix the score") lets the model satisfy each
 *       message by breaking the other field — the loop provably never converged
 *       when the first draft missed the narratives AND the score (the model
 *       re-answers only what the last correction asked for).</li>
 * </ol>
 *
 * <p>On success it normalizes the message to the clean extracted JSON via
 * {@link OutputGuardrail#successWith(String)} so the downstream typed
 * deserialization is fed fences-free, prose-free JSON. The checks operate on the
 * raw JSON tree (not the typed DTO) so a missing primitive field is caught as
 * "missing" and repaired, rather than silently defaulting to zero.
 *
 * <p>Entirely schema-shape driven and model-independent: the same guardrail
 * works for every model the engine is pointed at.
 */
public class StructuredOutputGuardrail implements OutputGuardrail {

    private final ObjectMapper mapper;
    private final List<String> requiredFields;
    private final String scoreField; // nullable — only score-bearing schemas set this
    /** Per-call attempt history sink; null when the caller doesn't want the trail. */
    private final AttemptLog attemptLog;

    /**
     * The raw text of the most recent response validated. Instances are built per AI
     * call (see {@link com.bvisionry.aiengine.service.AiEvaluationEngine} — one guardrail
     * per AiServices build), so this is effectively call-scoped; retained so the caller
     * can persist the offending output when the retry budget is exhausted.
     */
    private volatile String lastResponseText;

    public StructuredOutputGuardrail(ObjectMapper mapper, List<String> requiredFields, String scoreField) {
        this(mapper, requiredFields, scoreField, null);
    }

    public StructuredOutputGuardrail(ObjectMapper mapper, List<String> requiredFields, String scoreField,
                                     AttemptLog attemptLog) {
        this.mapper = mapper;
        this.requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
        this.scoreField = scoreField;
        this.attemptLog = attemptLog;
    }

    public String lastResponseText() {
        return lastResponseText;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        this.lastResponseText = responseFromLLM.text();
        String json = JsonExtraction.extract(responseFromLLM.text());
        if (json == null) {
            return rejected(
                    "The response did not contain a complete JSON object.",
                    "Respond with ONLY a single, complete JSON object matching the schema — "
                            + "no prose, no explanation, no markdown code fences.");
        }

        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            return rejected(
                    "The response was not valid JSON.",
                    "Return ONLY a valid JSON object matching the schema. No prose or code fences.");
        }
        if (node == null || !node.isObject()) {
            return rejected(
                    "The response was not a JSON object.",
                    "Return a single JSON object matching the schema.");
        }

        // Accumulate EVERY field violation before reprompting, so one corrective
        // message covers the whole contract (see class javadoc on convergence).
        List<String> violations = new ArrayList<>();

        List<String> missing = requiredFields.stream()
                .filter(f -> node.get(f) == null || node.get(f).isNull())
                .toList();
        if (!missing.isEmpty()) {
            violations.add("missing required field(s): " + String.join(", ", missing));
        }

        if (scoreField != null) {
            JsonNode score = node.get(scoreField);
            if (score == null || score.isNull() || !score.isIntegralNumber()) {
                violations.add("'" + scoreField + "' must be a concrete integer in [0, 100] — never null");
            } else {
                int value = score.asInt();
                if (value < 0 || value > 100) {
                    violations.add("'" + scoreField + "' is out of range: " + value);
                }
            }
        }

        if (!violations.isEmpty()) {
            return rejected(
                    "Invalid model output: " + String.join("; ", violations),
                    "Return ONLY one complete JSON object containing ALL of these fields, "
                            + "each present and valid: " + fullContract() + ". "
                            + "Keep the correct values you already produced and fix: "
                            + String.join("; ", violations) + ". No prose, no code fences.");
        }

        // Feed downstream deserialization the cleaned JSON (no fences/prose).
        if (attemptLog != null) {
            attemptLog.record(lastResponseText, AttemptLog.Outcome.ACCEPTED, null, null);
        }
        return successWith(json);
    }

    /**
     * Reprompt, recording the rejected draft and the corrective message in the
     * per-call {@link AttemptLog} first, so the audit trail carries the full repair
     * history rather than only the call's final state.
     */
    private OutputGuardrailResult rejected(String reason, String correction) {
        if (attemptLog != null) {
            attemptLog.record(lastResponseText, AttemptLog.Outcome.REPROMPTED, reason, correction);
        }
        return reprompt(reason, correction);
    }

    /** The complete field contract, restated verbatim in every corrective message. */
    private String fullContract() {
        List<String> all = new ArrayList<>();
        if (scoreField != null) {
            all.add(scoreField + " (integer 0-100)");
        }
        all.addAll(requiredFields);
        return String.join(", ", all);
    }
}
