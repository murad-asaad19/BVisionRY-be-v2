package com.bvisionry.aiengine.transport;

import com.bvisionry.aiconfig.service.AIConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1: the engine transport always speaks OpenRouter, so it must resolve the API key via
 * {@link AIConfigService#getDecryptedOpenRouterApiKey()} — never the provider-active
 * {@code getDecryptedApiKey()}. This guarantees that even when the {@code provider}
 * column says ANTHROPIC, the OpenRouter key (not the Anthropic key) is the one that
 * ever reaches {@code https://openrouter.ai/api/v1} as a Bearer token.
 */
class Lc4jChatModelProviderKeyRoutingTest {

    @Test
    void modelFor_usesOpenRouterKeySlot_notProviderActiveKey() {
        AIConfigService configService = mock(AIConfigService.class);
        ModelCapabilityRegistry registry = mock(ModelCapabilityRegistry.class);
        when(configService.getDecryptedOpenRouterApiKey()).thenReturn("sk-or-v1-realkey");
        when(registry.getCapabilities("anthropic/claude-sonnet-4"))
                .thenReturn(ModelCapabilities.conservative("anthropic/claude-sonnet-4"));

        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(configService, registry);
        // @Value fields aren't injected under a plain constructor; set the timeout so the
        // underlying client builder gets a valid (non-zero) duration.
        ReflectionTestUtils.setField(provider, "requestTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(provider, "maxRetries", 2);
        ReflectionTestUtils.setField(provider, "openRouterAppTitle", "BVisionRY");

        // Building the LangChain4j model does no network I/O, so a non-blank key is enough.
        assertThat(provider.modelFor("anthropic/claude-sonnet-4", 0.3, 1024)).isNotNull();

        // The transport must read the OpenRouter slot and must NOT consult the
        // provider-active accessor (which would return the Anthropic key here).
        verify(configService).getDecryptedOpenRouterApiKey();
        verify(configService, never()).getDecryptedApiKey();
    }
}
