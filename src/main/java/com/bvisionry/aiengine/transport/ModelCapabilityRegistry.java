package com.bvisionry.aiengine.transport;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Resolves {@link ModelCapabilities} for any model id, sourced live from
 * OpenRouter's own model metadata ({@code GET /api/v1/models}) so capability
 * detection never hard-codes a per-model table — add a model, the engine adapts.
 *
 * <p>Self-contained on purpose: it owns a minimal {@link RestClient} and parses
 * only the few fields it needs ({@code id}, {@code context_length},
 * {@code supported_parameters}), leaving the admin-facing {@code AIModelCatalogService}
 * and its DTOs untouched.
 *
 * <p>Degrades safely. The metadata is cached with a TTL; a fetch failure (no key,
 * network down, mock/e2e profile) never throws — it returns
 * {@link ModelCapabilities#conservative(String)}, which forces the universal
 * prompt+repair path that works for every model. Optimizations only ever turn on
 * with positive evidence from the provider.
 */
@Service
@Slf4j
public class ModelCapabilityRegistry {

    private static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

    private final AIConfigService configService;
    private final RestClient client;
    private final Duration cacheTtl;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public ModelCapabilityRegistry(
            AIConfigService configService,
            @Value("${bvisionry.ai.timeout.connect-seconds:10}") long connectTimeoutSeconds,
            @Value("${bvisionry.ai.timeout.read-seconds:60}") long readTimeoutSeconds,
            @Value("${bvisionry.ai.capabilities.cache-ttl-minutes:60}") long cacheTtlMinutes) {
        this.configService = configService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.client = RestClient.builder()
                .baseUrl(OPENROUTER_BASE_URL)
                .requestFactory(factory)
                .build();
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    /**
     * Capabilities for {@code modelId}. Refreshes the cache on demand (best
     * effort); returns conservative defaults when the model is unknown or
     * metadata is unavailable.
     */
    public ModelCapabilities getCapabilities(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return ModelCapabilities.conservative(modelId);
        }
        Map<String, ModelCapabilities> byId = ensureFresh();
        return byId.getOrDefault(modelId, ModelCapabilities.conservative(modelId));
    }

    private Map<String, ModelCapabilities> ensureFresh() {
        Snapshot current = snapshot.get();
        if (current.isFresh(cacheTtl)) {
            return current.byId();
        }
        Map<String, ModelCapabilities> refreshed = tryFetch();
        if (refreshed != null) {
            snapshot.set(new Snapshot(refreshed, Instant.now()));
            return refreshed;
        }
        // Fetch failed — keep serving the last known snapshot (possibly empty)
        // rather than throwing. Unknown models then resolve to conservative.
        return current.byId();
    }

    private Map<String, ModelCapabilities> tryFetch() {
        // Capabilities are fetched from OpenRouter's /models, so use the OpenRouter
        // key slot directly (same reasoning as the transport).
        String apiKey = configService.getDecryptedOpenRouterApiKey();
        try {
            RestClient.RequestHeadersSpec<?> req = client.get().uri("/models");
            if (apiKey != null && !apiKey.isBlank()) {
                req = req.header("Authorization", "Bearer " + apiKey);
            }
            ModelsResponse response = req.retrieve().body(ModelsResponse.class);
            if (response == null || response.data() == null) {
                return Map.of();
            }
            return response.data().stream()
                    .filter(d -> d.id() != null)
                    .collect(Collectors.toMap(
                            ModelData::id,
                            d -> ModelCapabilities.fromSupportedParameters(
                                    d.id(), d.context_length(), d.supported_parameters()),
                            (a, b) -> a,
                            ConcurrentHashMap::new));
        } catch (Exception e) {
            // No key (mock/e2e), network down, or schema drift — degrade silently.
            log.debug("Model capability fetch failed; using conservative defaults: {}", e.getMessage());
            return null;
        }
    }

    private record Snapshot(Map<String, ModelCapabilities> byId, Instant fetchedAt) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Instant.EPOCH);
        }

        boolean isFresh(Duration ttl) {
            return !byId.isEmpty() && Duration.between(fetchedAt, Instant.now()).compareTo(ttl) < 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelsResponse(List<ModelData> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelData(String id, int context_length, List<String> supported_parameters) {}
}
