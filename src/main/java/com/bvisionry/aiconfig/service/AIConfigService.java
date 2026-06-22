package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.AIConfigResponse;
import com.bvisionry.aiconfig.dto.AIConfigUpdateRequest;
import com.bvisionry.aiconfig.dto.ApiKeyUpdateRequest;
import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.repository.AIConfigurationRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.AIProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIConfigService {

    /**
     * Redis Pub/Sub channel for cross-instance cache invalidation (R1#13). On every
     * committed config/key write we publish here; each backend instance subscribes
     * (see {@code AIConfigCacheInvalidationConfig}) and drops its local memo on receipt,
     * so a rotation done on one node is reflected on all the others within a round-trip
     * instead of being bounded by the {@link #CONFIG_CACHE_TTL_MS} TTL.
     */
    public static final String INVALIDATE_CHANNEL = "ai-config:invalidate";

    private final AIConfigurationRepository configRepository;
    private final ApiKeyEncryptionService encryptionService;
    private final AuditService auditService;

    /**
     * Optional — present only when a Redis connection is configured. Field-injected
     * (not via the constructor) so the service still constructs cleanly in unit tests
     * and simply skips the cross-instance publish when Redis is absent. Local memo
     * invalidation always happens regardless, preserving single-instance correctness.
     */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

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
        invalidateAfterCommit();

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
        invalidateAfterCommit();

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
        AIConfiguration config = getConfigEntity();
        String encrypted = switch (config.getProvider()) {
            case OPENROUTER -> config.getOpenRouterApiKeyEncrypted();
            case ANTHROPIC -> config.getAnthropicApiKeyEncrypted();
        };
        return tryDecrypt(encrypted);
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
        AIConfiguration config = getConfigEntity();
        return tryDecrypt(config.getOpenRouterApiKeyEncrypted());
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

    /**
     * Drop the local in-process memo so the next read reloads. Public so the Redis
     * Pub/Sub listener can invoke it on a cross-instance invalidation signal (R1#13).
     */
    public void invalidateConfigCache() {
        cachedConfig = null;
    }

    /**
     * Invalidate the cache <em>after</em> the surrounding write commits (R1#6). Calling
     * {@link #invalidateConfigCache()} inline would let a concurrent {@link #getConfigEntity()}
     * reload the still-uncommitted row and re-memoize stale data for the full TTL. By
     * registering an {@link TransactionSynchronization#afterCommit()} callback we only
     * clear (and broadcast) once the new row is durably visible. When no transaction is
     * active (e.g. unit tests calling the service directly) we fall back to invalidating
     * immediately so single-call correctness still holds.
     */
    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishInvalidation();
                }
            });
        } else {
            publishInvalidation();
        }
    }

    /**
     * Clear the local memo and, when Redis is available, broadcast the invalidation to
     * the other instances (R1#13). The local clear always runs first so single-instance
     * correctness holds even with Redis absent; the publish is best-effort — if Redis is
     * down the platform degrades to exactly today's TTL-bounded staleness on peer nodes.
     */
    private void publishInvalidation() {
        invalidateConfigCache();
        StringRedisTemplate redis = this.stringRedisTemplate;
        if (redis == null) {
            return;
        }
        try {
            redis.convertAndSend(INVALIDATE_CHANNEL, "");
        } catch (RuntimeException e) {
            log.warn("Failed to publish AI-config cache invalidation to Redis ({}) — peer instances "
                    + "will refresh within the {}ms TTL instead.", e.getMessage(), CONFIG_CACHE_TTL_MS);
        }
    }

    /**
     * Decrypts a stored ciphertext, returning {@code null} (never throwing) when it
     * cannot be decrypted. The expected cause is a rotated {@code BVISIONRY_ENCRYPTION_KEY}:
     * AES-GCM then fails its auth-tag check ({@code AEADBadTagException}) and the
     * previously-stored keys are permanently undecryptable. An undecryptable key is
     * unusable, so callers treat {@code null} as "not configured". Crucially this keeps
     * the read paths ({@link #getConfig()}, model listing, evaluation) from hard-500ing
     * and bricking the very admin page used to re-enter the key. Blank/absent input also
     * returns {@code null}.
     */
    private String tryDecrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (RuntimeException e) {
            log.warn("Stored AI provider API key could not be decrypted (BVISIONRY_ENCRYPTION_KEY "
                    + "rotated since it was saved?). Treating it as not configured — re-enter the key "
                    + "from the AI Config admin page to fix.");
            return null;
        }
    }

    private KeySummary summarize(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return new KeySummary(false, null);
        }
        String decrypted = tryDecrypt(encrypted);
        if (decrypted == null) {
            // A key IS stored but can't be decrypted (encryption key rotated). Report it
            // as unconfigured so the UI prompts a re-entry, with a masked note explaining why.
            return new KeySummary(false, "Stored key can't be decrypted — re-enter to fix");
        }
        return new KeySummary(true, ApiKeyEncryptionService.maskApiKey(decrypted));
    }

    private record KeySummary(boolean configured, String masked) {}
}
