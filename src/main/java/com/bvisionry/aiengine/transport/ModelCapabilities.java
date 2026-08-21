package com.bvisionry.aiengine.transport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * What a given model can do, derived from the provider's own metadata rather
 * than a hard-coded per-model table. This is the heart of the model-agnostic
 * design: the engine adapts its request strategy (native structured output vs.
 * prompt-instructed JSON + repair) to whatever the configured model declares it
 * supports, so a new model is a config change with zero code change.
 *
 * <p>Capabilities come from two fields of OpenRouter's {@code GET /api/v1/models}:
 * {@code supported_parameters} for the request-shape flags, and {@code pricing}
 * for {@link #supportsExplicitPromptCaching()}. When a model is unknown or the
 * provider metadata is unavailable, {@link #conservative(String)} is used — it
 * disables every optimization and falls back to the universal path (prompt-
 * instructed JSON validated and repaired by guardrails), which works for
 * <em>every</em> model. Optimizations are opt-in on positive evidence; we never
 * assume.
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
        /**
         * Prompt caching that has to be ASKED for (a {@code cache_control} breakpoint),
         * as opposed to the automatic kind. Evidence: the model prices cache WRITES
         * ({@code pricing.input_cache_write} &gt; 0) — a write is only ever billed where
         * the caller creates the cache entry, so a write price is the provider saying
         * "caching here is explicit". Models that cache automatically (Anthropic aside,
         * that is most of them) price reads but not writes, and must NOT be sent the
         * directive: they would gain nothing and it is not part of their contract.
         */
        boolean supportsExplicitPromptCaching,
        /** Max context window in tokens (0 = unknown). */
        int contextLength
) {

    /**
     * Safe default for an unknown model or when provider metadata can't be
     * fetched: every optimization off, forcing the universal prompt+repair path.
     */
    public static ModelCapabilities conservative(String modelId) {
        return new ModelCapabilities(modelId, false, false, false, false, false, 0);
    }

    /**
     * Maps OpenRouter's model metadata to capability flags. Pure and side-effect
     * free so it is unit-testable without any network call.
     *
     * @param modelId              the model identifier (e.g. {@code anthropic/claude-sonnet-4})
     * @param contextLength        advertised context window, or 0 if unknown
     * @param supportedParams      OpenRouter's {@code supported_parameters}; null/empty ⇒ every
     *                             request-shape flag off
     * @param inputCacheWritePrice OpenRouter's {@code pricing.input_cache_write} as sent (a
     *                             decimal string); null/blank/zero/unparseable ⇒ no explicit
     *                             prompt caching
     */
    public static ModelCapabilities fromProviderMetadata(
            String modelId, int contextLength, List<String> supportedParams, String inputCacheWritePrice) {
        boolean explicitCaching = pricesCacheWrites(inputCacheWritePrice);
        if (supportedParams == null || supportedParams.isEmpty()) {
            return new ModelCapabilities(modelId, false, false, false, false, explicitCaching, contextLength);
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
                explicitCaching,
                contextLength);
    }

    /**
     * True when the provider quotes a positive price for writing a cache entry.
     * {@link BigDecimal} rather than {@code double} because the values arrive as
     * decimal strings with a lot of leading zeros (e.g. {@code "0.00000375"}) and
     * all we need is the sign — no rounding, no locale. Anything unparseable is
     * schema drift, and drift resolves to "off" like every other unknown here.
     */
    private static boolean pricesCacheWrites(String price) {
        if (price == null || price.isBlank()) {
            return false;
        }
        try {
            return new BigDecimal(price.trim()).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
