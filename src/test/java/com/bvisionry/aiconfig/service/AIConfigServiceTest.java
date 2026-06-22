package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.AIConfigResponse;
import com.bvisionry.aiconfig.dto.AIConfigUpdateRequest;
import com.bvisionry.aiconfig.dto.ApiKeyUpdateRequest;
import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.repository.AIConfigurationRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.AIProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AIConfigServiceTest {

    @Mock
    private AIConfigurationRepository configRepository;

    @Mock
    private ApiKeyEncryptionService encryptionService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AIConfigService configService;

    private AIConfiguration existingConfig;

    @BeforeEach
    void setUp() {
        existingConfig = new AIConfiguration();
        existingConfig.setId(UUID.randomUUID());
        existingConfig.setProvider(AIProvider.OPENROUTER);
        existingConfig.setDefaultEvaluationModel("anthropic/claude-sonnet-4");
        existingConfig.setDefaultInsightModel("anthropic/claude-sonnet-4");
        existingConfig.setEvaluationTemperature(new BigDecimal("0.30"));
        existingConfig.setInsightTemperature(new BigDecimal("0.70"));
        existingConfig.setMaxTokensEvaluation(2048);
        existingConfig.setMaxTokensInsight(4096);
    }

    @Test
    void getConfig_noApiKey_returnsConfiguredFalseForBothProviders() {
        when(configRepository.getSingleton()).thenReturn(existingConfig);

        AIConfigResponse response = configService.getConfig();

        assertThat(response.provider()).isEqualTo(AIProvider.OPENROUTER);
        assertThat(response.openRouterKeyConfigured()).isFalse();
        assertThat(response.openRouterKeyMasked()).isNull();
        assertThat(response.anthropicKeyConfigured()).isFalse();
        assertThat(response.anthropicKeyMasked()).isNull();
        assertThat(response.defaultEvaluationModel()).isEqualTo("anthropic/claude-sonnet-4");
    }

    @Test
    void getConfig_withOnlyOpenRouterKey_onlyOpenRouterReportsConfigured() {
        existingConfig.setOpenRouterApiKeyEncrypted("encrypted-or");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("encrypted-or")).thenReturn("sk-or-v1-realkey123");

        AIConfigResponse response = configService.getConfig();

        assertThat(response.openRouterKeyConfigured()).isTrue();
        assertThat(response.openRouterKeyMasked()).isNotNull();
        assertThat(response.openRouterKeyMasked()).doesNotContain("realkey123");
        assertThat(response.anthropicKeyConfigured()).isFalse();
    }

    @Test
    void getConfig_withBothKeys_bothReportConfigured() {
        existingConfig.setOpenRouterApiKeyEncrypted("encrypted-or");
        existingConfig.setAnthropicApiKeyEncrypted("encrypted-ant");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("encrypted-or")).thenReturn("sk-or-v1-rrr");
        when(encryptionService.decrypt("encrypted-ant")).thenReturn("sk-ant-api03-aaa");

        AIConfigResponse response = configService.getConfig();

        assertThat(response.openRouterKeyConfigured()).isTrue();
        assertThat(response.anthropicKeyConfigured()).isTrue();
    }

    @Test
    void getConfig_keyUndecryptable_reportsNotConfiguredInsteadOfThrowing() {
        // BVISIONRY_ENCRYPTION_KEY rotated since the key was saved: AES-GCM fails its
        // auth-tag check. getConfig must still load (so the admin page is reachable to
        // re-enter the key) and flag the key as unconfigured with a re-entry note.
        existingConfig.setOpenRouterApiKeyEncrypted("ciphertext-from-old-key");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("ciphertext-from-old-key"))
                .thenThrow(new RuntimeException("Failed to decrypt API key"));

        AIConfigResponse response = configService.getConfig();

        assertThat(response.openRouterKeyConfigured()).isFalse();
        assertThat(response.openRouterKeyMasked()).contains("re-enter");
        // The empty Anthropic slot is untouched — null, not the warning string.
        assertThat(response.anthropicKeyConfigured()).isFalse();
        assertThat(response.anthropicKeyMasked()).isNull();
    }

    @Test
    void getDecryptedApiKey_keyUndecryptable_returnsNull() {
        existingConfig.setOpenRouterApiKeyEncrypted("ciphertext-from-old-key");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("ciphertext-from-old-key"))
                .thenThrow(new RuntimeException("Failed to decrypt API key"));

        // Treated as "not configured" so callers surface a clean error, never a 500.
        assertThat(configService.getDecryptedApiKey()).isNull();
    }

    @Test
    void updateConfig_savesNewValues() {
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(configRepository.save(any())).thenReturn(existingConfig);

        AIConfigUpdateRequest request = new AIConfigUpdateRequest(
                AIProvider.ANTHROPIC,
                "claude-sonnet-4-5",
                "  ",
                "claude-sonnet-4-5",
                new BigDecimal("0.50"),
                new BigDecimal("0.80"),
                3000,
                5000
        );

        configService.updateConfig(request);

        ArgumentCaptor<AIConfiguration> captor = ArgumentCaptor.forClass(AIConfiguration.class);
        verify(configRepository).save(captor.capture());

        AIConfiguration saved = captor.getValue();
        assertThat(saved.getProvider()).isEqualTo(AIProvider.ANTHROPIC);
        assertThat(saved.getDefaultEvaluationModel()).isEqualTo("claude-sonnet-4-5");
        // Blank public-assessment model normalizes to null (= inherit default).
        assertThat(saved.getPublicAssessmentModel()).isNull();
        assertThat(saved.getEvaluationTemperature()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(saved.getMaxTokensEvaluation()).isEqualTo(3000);
    }

    @Test
    void updateApiKey_openRouter_writesToOpenRouterColumn() {
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.encrypt("sk-or-v1-newkey")).thenReturn("encrypted-or-new");
        when(configRepository.save(any())).thenReturn(existingConfig);

        configService.updateApiKey(new ApiKeyUpdateRequest(AIProvider.OPENROUTER, "sk-or-v1-newkey"));

        ArgumentCaptor<AIConfiguration> captor = ArgumentCaptor.forClass(AIConfiguration.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getOpenRouterApiKeyEncrypted()).isEqualTo("encrypted-or-new");
        assertThat(captor.getValue().getAnthropicApiKeyEncrypted()).isNull();

        verify(auditService).log(any(), eq(null), eq("API_KEY_UPDATED"), eq("AIConfiguration"), any(), any());
    }

    @Test
    void updateApiKey_anthropic_writesToAnthropicColumn() {
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.encrypt("sk-ant-api03-newkey")).thenReturn("encrypted-ant-new");
        when(configRepository.save(any())).thenReturn(existingConfig);

        configService.updateApiKey(new ApiKeyUpdateRequest(AIProvider.ANTHROPIC, "sk-ant-api03-newkey"));

        ArgumentCaptor<AIConfiguration> captor = ArgumentCaptor.forClass(AIConfiguration.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getAnthropicApiKeyEncrypted()).isEqualTo("encrypted-ant-new");
        assertThat(captor.getValue().getOpenRouterApiKeyEncrypted()).isNull();
    }

    @Test
    void getDecryptedApiKey_returnsActiveProviderKey() {
        existingConfig.setProvider(AIProvider.ANTHROPIC);
        existingConfig.setAnthropicApiKeyEncrypted("encrypted-ant");
        existingConfig.setOpenRouterApiKeyEncrypted("encrypted-or");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("encrypted-ant")).thenReturn("sk-ant-api03-realkey");

        String key = configService.getDecryptedApiKey();

        assertThat(key).isEqualTo("sk-ant-api03-realkey");
        verify(encryptionService, never()).decrypt("encrypted-or");
    }

    @Test
    void getDecryptedApiKey_activeProviderKeyMissing_returnsNull() {
        existingConfig.setProvider(AIProvider.ANTHROPIC);
        existingConfig.setOpenRouterApiKeyEncrypted("encrypted-or");
        when(configRepository.getSingleton()).thenReturn(existingConfig);

        assertThat(configService.getDecryptedApiKey()).isNull();
    }

    /**
     * C1: the engine transport reads the OpenRouter slot directly. Even when the
     * provider column says ANTHROPIC, getDecryptedOpenRouterApiKey must return the
     * OpenRouter key and NEVER the Anthropic key — so the wrong key can't reach
     * OpenRouter as a Bearer token.
     */
    @Test
    void getDecryptedOpenRouterApiKey_returnsOpenRouterKey_evenWhenProviderIsAnthropic() {
        existingConfig.setProvider(AIProvider.ANTHROPIC);
        existingConfig.setAnthropicApiKeyEncrypted("encrypted-ant");
        existingConfig.setOpenRouterApiKeyEncrypted("encrypted-or");
        when(configRepository.getSingleton()).thenReturn(existingConfig);
        when(encryptionService.decrypt("encrypted-or")).thenReturn("sk-or-v1-realkey");

        String key = configService.getDecryptedOpenRouterApiKey();

        assertThat(key).isEqualTo("sk-or-v1-realkey");
        verify(encryptionService, never()).decrypt("encrypted-ant");
    }

    @Test
    void getDecryptedOpenRouterApiKey_openRouterSlotEmpty_returnsNull() {
        existingConfig.setProvider(AIProvider.ANTHROPIC);
        existingConfig.setAnthropicApiKeyEncrypted("encrypted-ant");
        when(configRepository.getSingleton()).thenReturn(existingConfig);

        assertThat(configService.getDecryptedOpenRouterApiKey()).isNull();
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void newConfiguration_defaultsToOpenRouterProvider() {
        assertThat(new AIConfiguration().getProvider()).isEqualTo(AIProvider.OPENROUTER);
    }
}
