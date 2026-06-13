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

    @Transactional(readOnly = true)
    public AIConfiguration getConfigEntity() {
        return configRepository.getSingleton();
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
