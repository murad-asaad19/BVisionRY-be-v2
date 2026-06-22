package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.AIConfigResponse;
import com.bvisionry.aiconfig.dto.AIConfigUpdateRequest;
import com.bvisionry.aiconfig.dto.ApiKeyUpdateRequest;
import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.repository.AIConfigurationRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.AIProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AIConfigService {

    private final AIConfigurationRepository configRepository;
    private final ApiKeyEncryptionService encryptionService;
    private final AuditService auditService;

    /**
     * Short-lived memo of the singleton config (F18). It is read once per pillar on
     * every evaluation, so on the launch hot path the unmemoized version fired
     * millions of identical single-row SELECTs, each borrowing a pooled connection.
     * Cached in-process (not Redis — a JPA entity is awkward to serialize) for a
     * brief TTL and invalidated on every write below, so an admin change is picked
     * up within seconds. The entity is read-only on the eval path and only its basic
     * columns are touched, so handing out the detached instance is safe.
     */
    private static final long CONFIG_CACHE_TTL_MS = 30_000;
    private volatile AIConfiguration cachedConfig;
    private volatile long cachedConfigAt;

    @Transactional(readOnly = true)
    public AIConfigResponse getConfig() {
        AIConfiguration config = configRepository.getSingleton();

        KeySummary openRouter = summarize(config.getOpenRouterApiKeyEncrypted());
        KeySummary anthropic = summarize(config.getAnthropicApiKeyEncrypted());

        return new AIConfigResponse(
                config.getId(),
                config.getProvider(),
                openRouter.configured(),
                openRouter.masked(),
                anthropic.configured(),
                anthropic.masked(),
                config.getDefaultEvaluationModel(),
                config.getPublicAssessmentModel(),
                config.getDefaultInsightModel(),
                config.getEvaluationTemperature(),
                config.getInsightTemperature(),
                config.getMaxTokensEvaluation(),
                config.getMaxTokensInsight(),
                config.getUpdatedAt()
        );
    }

    @Transactional
    public AIConfigResponse updateConfig(AIConfigUpdateRequest request) {
        AIConfiguration config = configRepository.getSingleton();
        config.setProvider(request.provider());
        config.setDefaultEvaluationModel(request.defaultEvaluationModel());
        // Blank normalizes to null = "inherit the default evaluation model".
        String publicModel = request.publicAssessmentModel();
        config.setPublicAssessmentModel(
                publicModel == null || publicModel.isBlank() ? null : publicModel.trim());
        config.setDefaultInsightModel(request.defaultInsightModel());
        config.setEvaluationTemperature(request.evaluationTemperature());
        config.setInsightTemperature(request.insightTemperature());
        config.setMaxTokensEvaluation(request.maxTokensEvaluation());
        config.setMaxTokensInsight(request.maxTokensInsight());
        configRepository.save(config);
        invalidateConfigCache();

        auditService.log(null, null, "AI_CONFIG_UPDATED", "AIConfiguration", config.getId(),
                Map.of("provider", request.provider().name(),
                       "evaluationModel", request.defaultEvaluationModel(),
                       "publicAssessmentModel",
                       config.getPublicAssessmentModel() == null ? "(inherit)" : config.getPublicAssessmentModel(),
                       "insightModel", request.defaultInsightModel()));

        return getConfig();
    }

    @Transactional
    public void updateApiKey(ApiKeyUpdateRequest request) {
        AIConfiguration config = configRepository.getSingleton();
        String encrypted = encryptionService.encrypt(request.apiKey());
        switch (request.provider()) {
            case OPENROUTER -> config.setOpenRouterApiKeyEncrypted(encrypted);
            case ANTHROPIC -> config.setAnthropicApiKeyEncrypted(encrypted);
        }
        configRepository.save(config);
        invalidateConfigCache();

        auditService.log(null, null, "API_KEY_UPDATED", "AIConfiguration", config.getId(),
                Map.of("provider", request.provider().name()));
    }

    /**
     * Returns the decrypted API key for the currently-active provider (i.e. the
     * one that {@link AIConfiguration#getProvider()} points to). Returns null if
     * that provider's key slot is empty. Callers should treat null as "not
     * configured" and surface a user-facing error.
     */
    @Transactional(readOnly = true)
    public String getDecryptedApiKey() {
        AIConfiguration config = configRepository.getSingleton();
        String encrypted = switch (config.getProvider()) {
            case OPENROUTER -> config.getOpenRouterApiKeyEncrypted();
            case ANTHROPIC -> config.getAnthropicApiKeyEncrypted();
        };
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(encrypted);
    }

    /**
     * Returns the decrypted <em>OpenRouter</em> key, regardless of the {@code provider}
     * column. The engine transport ({@code Lc4jChatModelProvider}) and the model
     * capability lookup always talk to OpenRouter's OpenAI-compatible endpoint, so they
     * must read this slot and never the provider-active key — otherwise an admin who
     * selected "Anthropic" would ship the Anthropic key to OpenRouter as a Bearer token
     * (→ 401). Returns null when the OpenRouter slot is empty.
     */
    @Transactional(readOnly = true)
    public String getDecryptedOpenRouterApiKey() {
        AIConfiguration config = configRepository.getSingleton();
        String encrypted = config.getOpenRouterApiKeyEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(encrypted);
    }

    /**
     * Hot-path accessor used per-pillar during evaluation. Intentionally NOT
     * {@code @Transactional}: a cache hit must not borrow a pooled connection. On a
     * miss the repository call opens its own short transaction. See the cache fields.
     */
    public AIConfiguration getConfigEntity() {
        AIConfiguration cached = cachedConfig;
        if (cached != null && (System.currentTimeMillis() - cachedConfigAt) < CONFIG_CACHE_TTL_MS) {
            return cached;
        }
        AIConfiguration fresh = configRepository.getSingleton();
        cachedConfig = fresh;
        cachedConfigAt = System.currentTimeMillis();
        return fresh;
    }

    /** Drop the memo so the next read reloads — called after any config/key write. */
    private void invalidateConfigCache() {
        cachedConfig = null;
    }

    private KeySummary summarize(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return new KeySummary(false, null);
        }
        String decrypted = encryptionService.decrypt(encrypted);
        return new KeySummary(true, ApiKeyEncryptionService.maskApiKey(decrypted));
    }

    private record KeySummary(boolean configured, String masked) {}
}
