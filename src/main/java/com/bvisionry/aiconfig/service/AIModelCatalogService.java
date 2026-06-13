package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.OpenRouterModel;
import com.bvisionry.common.enums.AIProvider;
import com.bvisionry.common.exception.AIServiceException;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Proxies the upstream provider's model list so the frontend never sees the API key.
 * Branches on the configured provider:
 * <ul>
 *   <li>OPENROUTER: {@code GET https://openrouter.ai/api/v1/models}</li>
 *   <li>ANTHROPIC:  {@code GET https://api.anthropic.com/v1/models}</li>
 * </ul>
 * Both responses are normalized to {@link OpenRouterModel}. Anthropic's payload has no
 * pricing or context_length, so those fields are null / 0 for ANTHROPIC results.
 *
 * <p>Both {@link RestClient}s are built with explicit connect/read timeouts so an
 * unreachable provider can't pin the admin "available models" call open
 * indefinitely. Defaults are configurable via
 * {@code bvisionry.ai.timeout.connect-seconds} (10) and
 * {@code bvisionry.ai.timeout.read-seconds} (60).
 */
@Service
@Slf4j
public class AIModelCatalogService {

    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1";
    private static final String ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_API_VERSION = "2023-06-01";

    private final AIConfigService configService;
    private final RestClient openRouterClient;
    private final RestClient anthropicClient;

    public AIModelCatalogService(
            AIConfigService configService,
            @Value("${bvisionry.ai.timeout.connect-seconds:10}") long connectTimeoutSeconds,
            @Value("${bvisionry.ai.timeout.read-seconds:60}") long readTimeoutSeconds) {
        this.configService = configService;
        SimpleClientHttpRequestFactory factory = buildRequestFactory(connectTimeoutSeconds, readTimeoutSeconds);
        this.openRouterClient = RestClient.builder()
                .baseUrl(OPENROUTER_MODELS_URL)
                .requestFactory(factory)
                .build();
        this.anthropicClient = RestClient.builder()
                .baseUrl(ANTHROPIC_MODELS_URL)
                .requestFactory(factory)
                .build();
    }

    private static SimpleClientHttpRequestFactory buildRequestFactory(long connectSeconds, long readSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        return factory;
    }

    public List<OpenRouterModel> getAvailableModels() {
        String apiKey = configService.getDecryptedApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIServiceException("AI provider API key is not configured");
        }

        AIProvider provider = configService.getConfigEntity().getProvider();
        return switch (provider) {
            case OPENROUTER -> fetchOpenRouter(apiKey);
            case ANTHROPIC -> fetchAnthropic(apiKey);
        };
    }

    private List<OpenRouterModel> fetchOpenRouter(String apiKey) {
        try {
            OpenRouterModel.OpenRouterModelsResponse response = openRouterClient.get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(OpenRouterModel.OpenRouterModelsResponse.class);

            if (response == null || response.data() == null) return List.of();

            return response.data().stream()
                    .map(data -> new OpenRouterModel(
                            data.id(),
                            data.name(),
                            data.description(),
                            data.pricing() != null
                                    ? new OpenRouterModel.Pricing(data.pricing().prompt(), data.pricing().completion())
                                    : null,
                            data.context_length()
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch models from OpenRouter", e);
            throw new AIServiceException("Failed to fetch models from OpenRouter: " + e.getMessage(), e);
        }
    }

    private List<OpenRouterModel> fetchAnthropic(String apiKey) {
        try {
            AnthropicModelsResponse response = anthropicClient.get()
                    .uri("/models")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_API_VERSION)
                    .retrieve()
                    .body(AnthropicModelsResponse.class);

            if (response == null || response.data() == null) return List.of();

            return response.data().stream()
                    .map(m -> new OpenRouterModel(m.id(), m.displayName(), null, null, 0))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch models from Anthropic", e);
            throw new AIServiceException("Failed to fetch models from Anthropic: " + e.getMessage(), e);
        }
    }

    private record AnthropicModelsResponse(List<AnthropicModel> data) {}

    private record AnthropicModel(
            String id,
            @JsonProperty("display_name") String displayName
    ) {}
}
