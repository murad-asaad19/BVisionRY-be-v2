package com.bvisionry.aiengine.transport;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single transport seam for the rebuilt engine: builds a LangChain4j
 * {@link ChatModel} for any model id, always through OpenRouter's
 * <em>OpenAI-compatible</em> endpoint. One wire format — request, response,
 * usage, errors — serves every model OpenRouter hosts (OpenAI, Anthropic,
 * Google, Mistral, …), which is what makes the pipeline genuinely model-agnostic.
 *
 * <p>Request strategy is capability-gated via {@link ModelCapabilityRegistry}:
 * when the configured model declares strict structured-output support, the model
 * is built with {@link Capability#RESPONSE_FORMAT_JSON_SCHEMA} + strict JSON
 * schema so the provider enforces shape (fewer repair round-trips, higher
 * accuracy). Otherwise the model is plain and shape is enforced downstream by the
 * output guardrails — the universal path that works for every model.
 *
 * <p>Prompt caching is gated the same way, on the same principle. A caller asks for
 * it per call ({@code cachePrompt}); it is only ever sent to a model whose metadata
 * says caching there is explicit, so the directive never rides along to a provider
 * that did not ask for it. See {@link ModelCapabilities#supportsExplicitPromptCaching()}.
 *
 * <p>Built models are cached and reused; the cache key folds in a collision-resistant
 * (SHA-256) fingerprint of the API key so a key rotation transparently rebuilds the
 * client and never reuses a stale-key model.
 */
@Slf4j
@RequiredArgsConstructor
public class Lc4jChatModelProvider {

    /** OpenRouter's OpenAI-compatible base; LangChain4j appends {@code /chat/completions}. */
    private static final String OPENROUTER_OPENAI_BASE_URL = "https://openrouter.ai/api/v1";

    /**
     * OpenRouter's request-root prompt-caching directive: it places the cache
     * breakpoint on the last cacheable block, so the whole prompt becomes the
     * cached prefix and a REPEAT of the same prompt reads it back instead of being
     * billed again. {@code ephemeral} is the 5-minute tier (writes 1.25×, reads
     * 0.1× base input); the 1-hour tier ({@code "ttl": "1h"}) doubles the write and
     * is not worth it for a reviewer pressing Regenerate seconds later.
     */
    private static final Map<String, Object> CACHE_CONTROL_EPHEMERAL =
            Map.of("cache_control", Map.of("type", "ephemeral"));

    private final AIConfigService configService;
    private final ModelCapabilityRegistry capabilityRegistry;

    @Value("${bvisionry.ai.timeout.request-seconds:300}")
    private long requestTimeoutSeconds;

    @Value("${bvisionry.ai.max-retries:2}")
    private int maxRetries;

    /** Optional OpenRouter app-ranking header; skipped when blank. */
    @Value("${bvisionry.ai.openrouter.referer:}")
    private String openRouterReferer;

    @Value("${bvisionry.ai.openrouter.app-title:Bvisionry}")
    private String openRouterAppTitle;

    /**
     * Optional OpenRouter routing-variant suffix applied to the model id ON THE WIRE
     * only — "nitro" sorts providers by throughput (fastest tok/s), "floor" by price.
     * Blank = OpenRouter default routing. Deliberately kept out of the capability
     * lookup and the cache key (both key on the base id): OpenRouter's /models list
     * has no variant entries, so a suffixed id would miss structured-output detection
     * and silently fall back to guardrail-only mode.
     */
    @Value("${bvisionry.ai.openrouter.routing-variant:}")
    private String routingVariant;

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    /**
     * A {@link ChatModel} for {@code modelName} tuned for one call type.
     *
     * <p>This is the single seam between the engine and the network: the mock and
     * e2e transports exist by overriding exactly this method, so it deliberately
     * has NO convenience overload — an overload would be a way for a call to slip
     * past a mock and reach (and bill) the real provider.
     *
     * @param modelName   provider model id, e.g. {@code anthropic/claude-sonnet-4}
     * @param temperature sampling temperature
     * @param maxTokens   max output tokens
     * @param cachePrompt ask the provider to cache this prompt, so a repeat of it reads
     *                    the cache instead of being billed again. Honoured only where the
     *                    model declares explicit caching; pass {@code true}
     *                    for calls whose input genuinely repeats, {@code false} otherwise — a cache
     *                    write costs 1.25× and a prompt that never repeats never earns it back.
     */
    public ChatModel modelFor(String modelName, double temperature, int maxTokens, boolean cachePrompt) {
        if (modelName == null || modelName.isBlank()) {
            throw new AIServiceException("No AI model configured for this call.");
        }
        // The transport always speaks to OpenRouter, so it must read the OpenRouter
        // key slot — never the provider-active key. This is what makes it impossible
        // to ship an Anthropic key to OpenRouter when the provider column says ANTHROPIC.
        String apiKey = configService.getDecryptedOpenRouterApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIServiceException("AI provider API key is not configured. Set it in the admin panel.");
        }

        ModelCapabilities caps = capabilityRegistry.getCapabilities(modelName);
        boolean promptCaching = cachePrompt && caps.supportsExplicitPromptCaching();
        // Fold a collision-resistant digest of the key material — never String.hashCode(),
        // whose 32-bit space can collide an old and a rotated key onto the same cache slot
        // and so reuse a ChatModel built with the stale key (401s until restart).
        String cacheKey = String.join("|",
                modelName,
                Double.toString(temperature),
                Integer.toString(maxTokens),
                Boolean.toString(caps.supportsStructuredOutputs()),
                Boolean.toString(promptCaching),
                apiKeyFingerprint(apiKey));

        return cache.computeIfAbsent(cacheKey,
                k -> build(modelName, temperature, maxTokens, apiKey, caps, promptCaching));
    }

    /**
     * A {@link StreamingChatModel} for {@code modelName} — same OpenRouter
     * transport, key handling and headers as {@link #modelFor}, but streaming
     * (SSE) so callers can forward tokens as they arrive. Structured-output
     * capability gating is deliberately skipped: streaming callers enforce
     * shape downstream (a strict schema would buffer, defeating the stream).
     */
    public StreamingChatModel streamingModelFor(String modelName, double temperature, int maxTokens) {
        if (modelName == null || modelName.isBlank()) {
            throw new AIServiceException("No AI model configured for this call.");
        }
        String apiKey = configService.getDecryptedOpenRouterApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIServiceException("AI provider API key is not configured. Set it in the admin panel.");
        }
        String cacheKey = String.join("|",
                "streaming",
                modelName,
                Double.toString(temperature),
                Integer.toString(maxTokens),
                apiKeyFingerprint(apiKey));
        return streamingCache.computeIfAbsent(cacheKey, k -> OpenAiStreamingChatModel.builder()
                .baseUrl(OPENROUTER_OPENAI_BASE_URL)
                .apiKey(apiKey)
                .modelName(applyRoutingVariant(modelName))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .customHeaders(buildHeaders())
                .build());
    }

    private ChatModel build(String modelName, double temperature, int maxTokens, String apiKey,
                            ModelCapabilities caps, boolean promptCaching) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(OPENROUTER_OPENAI_BASE_URL)
                .apiKey(apiKey)
                .modelName(applyRoutingVariant(modelName))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .maxRetries(maxRetries)
                .customHeaders(buildHeaders());

        if (caps.supportsStructuredOutputs()) {
            // Provider-enforced schema: the model can't return the wrong shape.
            builder.supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                    .strictJsonSchema(true);
            log.debug("Model '{}' supports strict structured output — enabling native JSON schema.", modelName);
        } else {
            log.debug("Model '{}' lacks strict structured output — relying on prompt + output guardrails.", modelName);
        }

        if (promptCaching) {
            // Lands as a TOP-LEVEL body field: LangChain4j threads customParameters
            // through to ChatCompletionRequest, whose accessor is @JsonAnyGetter, so
            // the map is flattened into the request root rather than nested under a
            // property. That is where OpenRouter reads the directive.
            builder.customParameters(CACHE_CONTROL_EPHEMERAL);
            log.debug("Model '{}' prices cache writes — asking OpenRouter to cache this prompt.", modelName);
        }

        return builder.build();
    }

    /**
     * Append the configured OpenRouter routing variant (e.g. ":nitro") to the wire
     * model id. No-op when unset, or when the id ALREADY carries a variant suffix so
     * we never double-suffix an explicitly chosen route.
     *
     * <p>An OpenRouter id is {@code vendor/slug[:variant]} and may carry at most one
     * variant suffix — the colon that denotes it appears AFTER the {@code /}. A raw
     * {@code modelName.contains(":")} therefore wrongly treats legitimate ids whose
     * SLUG contains a colon (e.g. {@code deepseek/deepseek-r1:free}) as already-pinned
     * and never applies the configured variant. We detect a variant by looking for a
     * colon only in the segment after the last {@code /}.
     */
    private String applyRoutingVariant(String modelName) {
        if (routingVariant == null || routingVariant.isBlank() || hasVariantSuffix(modelName)) {
            return modelName;
        }
        return modelName + ":" + routingVariant.strip();
    }

    /** True when the id already pins a variant: a ":" in the segment after the last "/". */
    private static boolean hasVariantSuffix(String modelName) {
        int slash = modelName.lastIndexOf('/');
        return modelName.indexOf(':', slash + 1) >= 0;
    }

    /**
     * Collision-resistant fingerprint of the API key for use as a cache-key component.
     * SHA-256 over the raw key bytes — distinct keys map to distinct fingerprints with
     * overwhelming probability, so a rotated key always builds a fresh ChatModel rather
     * than colliding onto a stale-key entry. The digest (not the key) is what lands in
     * the in-memory cache key.
     */
    private static String apiKeyFingerprint(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JRE; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (openRouterReferer != null && !openRouterReferer.isBlank()) {
            headers.put("HTTP-Referer", openRouterReferer);
        }
        if (openRouterAppTitle != null && !openRouterAppTitle.isBlank()) {
            headers.put("X-Title", openRouterAppTitle);
        }
        return headers;
    }
}
