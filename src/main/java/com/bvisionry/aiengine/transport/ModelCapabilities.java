package com.bvisionry.aiengine.transport;

import java.util.List;
import java.util.Set;

/**
 * What a given model can do, derived from the provider's own metadata rather
 * than a hard-coded per-model table. This is the heart of the model-agnostic
 * design: the engine adapts its request strategy (native structured output vs.
 * prompt-instructed JSON + repair) to whatever the configured model declares it
 * supports, so a new model is a config change with zero code change.
 *
 * <p>Capabilities map directly to OpenRouter's {@code supported_parameters}
 * field on {@code GET /api/v1/models}. When a model is unknown or the provider
 * metadata is unavailable, {@link #conservative(String)} is used — it disables
 * every optimization and falls back to the universal path (prompt-instructed
 * JSON validated and repaired by guardrails), which works for <em>every</em>
 * model. Optimizations are opt-in on positive evidence; we never assume.
 */
public record ModelCapabilities(
        String modelId,
        /** Strict JSON-schema response format ({@code structured_outputs}) — the model guarantees shape. */
        boolean supportsStructuredOutputs,
        /** Loose {@code json_object} response format — JSON without a schema guarantee. */
        boolean supportsJsonObject,
        /** Native tool / function calling. */
        boolean supportsTools,
        /** Deterministic sampling via {@code seed} — used for reproducible scoring. */
        boolean supportsSeed,
        /** Max context window in tokens (0 = unknown). */
        int contextLength
) {

    /**
     * Safe default for an unknown model or when provider metadata can't be
     * fetched: every optimization off, forcing the universal prompt+repair path.
     */
    public static ModelCapabilities conservative(String modelId) {
        return new ModelCapabilities(modelId, false, false, false, false, 0);
    }

    /**
     * Maps OpenRouter's {@code supported_parameters} list to capability flags.
     * Pure and side-effect free so it is unit-testable without any network call.
     *
     * @param modelId           the model identifier (e.g. {@code anthropic/claude-sonnet-4})
     * @param contextLength     advertised context window, or 0 if unknown
     * @param supportedParams   OpenRouter's {@code supported_parameters}; null/empty ⇒ conservative
     */
    public static ModelCapabilities fromSupportedParameters(
            String modelId, int contextLength, List<String> supportedParams) {
        if (supportedParams == null || supportedParams.isEmpty()) {
            return new ModelCapabilities(modelId, false, false, false, false, contextLength);
        }
        Set<String> params = supportedParams.stream()
                .filter(p -> p != null)
                .map(p -> p.trim().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
        return new ModelCapabilities(
                modelId,
                params.contains("structured_outputs"),
                params.contains("response_format"),
                params.contains("tools"),
                params.contains("seed"),
                contextLength);
    }
}
