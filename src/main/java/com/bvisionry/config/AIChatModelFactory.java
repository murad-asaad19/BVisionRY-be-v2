package com.bvisionry.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.common.enums.AIProvider;
import com.bvisionry.common.exception.AIServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

/**
 * Builds a cached {@link AnthropicChatModel} that can talk to either:
 *
 * <ul>
 *   <li><b>OPENROUTER</b>: OpenRouter's Anthropic-compatible endpoint at
 *       {@code https://openrouter.ai/api/v1/messages}. OpenRouter expects
 *       {@code Authorization: Bearer <key>}, mapped to the SDK's {@code authToken}.</li>
 *   <li><b>ANTHROPIC</b>: Anthropic's native API at {@code https://api.anthropic.com}.
 *       Uses the SDK default base URL and {@code x-api-key} header (via {@code apiKey}).
 *       Required if you want native prompt caching to actually register — OpenRouter
 *       routes requests through Bedrock/Vertex/Anthropic interchangeably, and
 *       cache_control headers don't survive the Bedrock route.</li>
 * </ul>
 *
 * The client is rebuilt only when the stored provider or API key changes.
 *
 * <p>HTTP timeouts are bounded well under the Anthropic SDK default of 10
 * minutes so a hung TCP connection or stalled upstream queue can't silently
 * consume a request thread for the entire interval. We cap the whole call
 * at {@code bvisionry.ai.timeout.request-seconds} (default 5 minutes) — the
 * overall-summary call legitimately needs more headroom than the per-pillar
 * calls because its prompt carries every pillar's output plus raw excerpts,
 * and evaluation runs async on {@code evaluationExecutor} so a slower call
 * doesn't block any HTTP thread. If the call still exceeds the ceiling it
 * surfaces to the FAILED-submission retry path.
 *
 * <p>Registered as a Spring bean by {@link AIConfig#aiChatModelFactory(AIConfigService)} —
 * not via {@code @Component} so the e2e source set can shadow it cleanly without
 * leaving a dangling production instance in the context.
 */
@RequiredArgsConstructor
public class AIChatModelFactory {

    private static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api";

    private final AIConfigService configService;

    @Value("${bvisionry.ai.timeout.connect-seconds:10}")
    private long connectTimeoutSeconds;

    @Value("${bvisionry.ai.timeout.read-seconds:300}")
    private long readTimeoutSeconds;

    @Value("${bvisionry.ai.timeout.request-seconds:300}")
    private long requestTimeoutSeconds;

    private volatile AnthropicChatModel cachedModel;
    private volatile String cachedClientKey;

    public ChatModel create() {
        String apiKey = configService.getDecryptedApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIServiceException("AI provider API key is not configured. Set it in the admin panel.");
        }

        AIProvider provider = configService.getConfigEntity().getProvider();
        String clientKey = provider.name() + "|" + apiKey.hashCode();

        if (cachedModel != null && clientKey.equals(cachedClientKey)) {
            return cachedModel;
        }

        synchronized (this) {
            if (cachedModel != null && clientKey.equals(cachedClientKey)) {
                return cachedModel;
            }

            cachedModel = AnthropicChatModel.builder()
                    .anthropicClient(buildClient(provider, apiKey))
                    .build();
            cachedClientKey = clientKey;

            return cachedModel;
        }
    }

    private AnthropicClient buildClient(AIProvider provider, String apiKey) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .timeout(buildTimeout());
        return switch (provider) {
            // OpenRouter uses Bearer auth (authToken) and a non-default base URL.
            case OPENROUTER -> builder.baseUrl(OPENROUTER_BASE_URL).authToken(apiKey).build();
            // Anthropic native: default base URL (api.anthropic.com), x-api-key header.
            case ANTHROPIC -> builder.apiKey(apiKey).build();
        };
    }

    /**
     * Bounded {@link Timeout} so a stalled upstream can never consume a
     * request thread for the SDK's 10-minute default. Connect / read /
     * overall-request limits are configurable independently — connect is
     * short because TCP handshake is cheap; read covers idle gaps between
     * SSE chunks in streaming mode (we don't stream today, but it keeps
     * the door open); request is the hard ceiling.
     */
    private Timeout buildTimeout() {
        return Timeout.builder()
                .connect(Duration.ofSeconds(connectTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
    }
}
