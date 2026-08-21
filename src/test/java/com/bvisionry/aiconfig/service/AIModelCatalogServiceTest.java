package com.bvisionry.aiconfig.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The unconfigured platform. {@code getAvailableModels()} used to throw
 * {@code AIServiceException} when no API key was set, which
 * {@code GlobalExceptionHandler} maps to 503 — so the admin AI-config console
 * and the pillar editor's embedded model picker logged a failed request on
 * every page load of a FRESH install, and three e2e specs that assert zero
 * console errors failed. "No key yet" is the first-run state of every install,
 * not a service outage.
 */
class AIModelCatalogServiceTest {

    private final AIConfigService configService = mock(AIConfigService.class);
    private final AIModelCatalogService service = new AIModelCatalogService(configService, 10, 60);

    @Test
    void returnsAnEmptyCatalogWhenNoApiKeyIsConfigured() {
        when(configService.getDecryptedApiKey()).thenReturn(null);

        assertThat(service.getAvailableModels()).isEmpty();
        // Never reaches the provider branch — nothing to ask a provider for.
        verify(configService, never()).getConfigEntity();
    }

    @Test
    void returnsAnEmptyCatalogWhenTheApiKeyIsBlank() {
        when(configService.getDecryptedApiKey()).thenReturn("   ");

        assertThat(service.getAvailableModels()).isEmpty();
        verify(configService, never()).getConfigEntity();
    }
}
