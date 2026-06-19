package com.bvisionry.aiengine.transport;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
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
 * <p>Built models are cached and reused; the cache key folds in the API-key hash
 * so a key rotation transparently rebuilds the client.
 */
@Slf4j
@RequiredArgsConstructor
public class Lc4jChatModelProvider {

    /** OpenRouter's OpenAI-compatible base; LangChain4j appends {@code /chat/completions}. */
    private static final String OPENROUTER_OPENAI_BASE_URL = "https://openrouter.ai/api/v1";

    private final AIConfigService configService;
    private final ModelCapabilityRegistry capabilityRegistry;

    @Value("${bvisionry.ai.timeout.request-seconds:300}")
    private long requestTimeoutSeconds;

    @Value("${bvisionry.ai.max-retries:2}")
    private int maxRetries;

    /** Optional OpenRouter app-ranking header; skipped when blank. */
    @Value("${bvisionry.ai.openrouter.referer:}")
    private String openRouterReferer;

    @Value("${bvisionry.ai.openrouter.app-title:BVisionRY}")
    private String openRouterAppTitle;

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * A {@link ChatModel} for {@code modelName} tuned for one call type.
     *
     * @param modelName   provider model id, e.g. {@code anthropic/claude-sonnet-4}
     * @param temperature sampling temperature
     * @param maxTokens   max output tokens
     */
    public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
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
        String cacheKey = String.join("|",
                modelName,
                Double.toString(temperature),
                Integer.toString(maxTokens),
                Boolean.toString(caps.supportsStructuredOutputs()),
                Integer.toString(apiKey.hashCode()));

        return cache.computeIfAbsent(cacheKey, k -> build(modelName, temperature, maxTokens, apiKey, caps));
    }

    private ChatModel build(String modelName, double temperature, int maxTokens, String apiKey,
                            ModelCapabilities caps) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(OPENROUTER_OPENAI_BASE_URL)
                .apiKey(apiKey)
                .modelName(modelName)
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

        return builder.build();
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
