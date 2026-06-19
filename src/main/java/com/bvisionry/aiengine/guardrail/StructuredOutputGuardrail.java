package com.bvisionry.aiengine.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.List;

/**
 * The model-agnostic robustness core: a declarative output guardrail that
 * validates a model's response and, on failure, triggers a <em>reprompt</em> so
 * LangChain4j re-asks the model with a corrective instruction (up to the
 * configured retry budget) instead of silently accepting garbage.
 *
 * <p>Checks, in order:
 * <ol>
 *   <li>a single balanced JSON object is present ({@link JsonExtraction}) — else reprompt (F1–F3);</li>
 *   <li>it parses as a JSON object — else reprompt;</li>
 *   <li>all {@code requiredFields} are present and non-null — else reprompt (F4);</li>
 *   <li>if a {@code scoreField} is given, it is an integer in [0,100] — else reprompt (F6).</li>
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

    public StructuredOutputGuardrail(ObjectMapper mapper, List<String> requiredFields, String scoreField) {
        this.mapper = mapper;
        this.requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
        this.scoreField = scoreField;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String json = JsonExtraction.extract(responseFromLLM.text());
        if (json == null) {
            return reprompt(
                    "The response did not contain a complete JSON object.",
                    "Respond with ONLY a single, complete JSON object matching the schema — "
                            + "no prose, no explanation, no markdown code fences.");
        }

        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            return reprompt(
                    "The response was not valid JSON.",
                    "Return ONLY a valid JSON object matching the schema. No prose or code fences.");
        }
        if (node == null || !node.isObject()) {
            return reprompt(
                    "The response was not a JSON object.",
                    "Return a single JSON object matching the schema.");
        }

        List<String> missing = requiredFields.stream()
                .filter(f -> node.get(f) == null || node.get(f).isNull())
                .toList();
        if (!missing.isEmpty()) {
            return reprompt(
                    "Missing required field(s): " + String.join(", ", missing),
                    "Include every required field and return ONLY the JSON object. "
                            + "Missing: " + String.join(", ", missing));
        }

        if (scoreField != null) {
            JsonNode score = node.get(scoreField);
            if (score == null || score.isNull() || !score.isIntegralNumber()) {
                return reprompt(
                        "Field '" + scoreField + "' must be an integer in [0, 100].",
                        "Set '" + scoreField + "' to an integer between 0 and 100, "
                                + "and return ONLY the JSON object.");
            }
            int value = score.asInt();
            if (value < 0 || value > 100) {
                return reprompt(
                        "Field '" + scoreField + "' is out of range: " + value,
                        "Set '" + scoreField + "' to an integer between 0 and 100. "
                                + "Return ONLY the corrected JSON object.");
            }
        }

        // Feed downstream deserialization the cleaned JSON (no fences/prose).
        return successWith(json);
    }
}
