package com.bvisionry.aiengine.transport;

import com.bvisionry.aiconfig.service.AIConfigService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The prompt-caching directive is what turns OpenRouter's caching on for models
 * whose caching is explicit (Anthropic's) — without it every repeat of an identical
 * prompt is billed in full. These assert the two halves of the gate: the caller has
 * to ask, AND the model's metadata has to say caching there is explicit. Building a
 * LangChain4j model does no network I/O, so this needs neither a key nor a server.
 */
class Lc4jChatModelProviderPromptCachingTest {

    private static final String CACHING_MODEL = "anthropic/claude-sonnet-4";
    private static final String AUTO_CACHING_MODEL = "openai/gpt-4o";

    @Test
    void sendsTheDirective_whenTheCallerAsksAndTheModelPricesCacheWrites() {
        assertThat(customParametersOf(CACHING_MODEL, "0.00000375", true))
                .isEqualTo(Map.of("cache_control", Map.of("type", "ephemeral")));
    }

    @Test
    void omitsTheDirective_whenTheCallerDoesNotAsk() {
        assertThat(customParametersOf(CACHING_MODEL, "0.00000375", false)).isNullOrEmpty();
    }

    /**
     * A model that caches automatically prices reads but not writes. Sending it the
     * directive would buy nothing and is not part of its contract, so the gate holds
     * even though the caller asked.
     */
    @Test
    void omitsTheDirective_whenTheModelCachesAutomatically() {
        assertThat(customParametersOf(AUTO_CACHING_MODEL, null, true)).isNullOrEmpty();
    }

    /**
     * Caching and non-caching models of the SAME name/temperature/token budget must not
     * collide in the built-model cache — otherwise whichever call type ran first would
     * decide caching for the other.
     */
    @Test
    void cachingAndNonCachingModelsAreCachedSeparately() {
        Lc4jChatModelProvider provider = provider(CACHING_MODEL, "0.00000375");

        ChatModel cached = provider.modelFor(CACHING_MODEL, 0.7, 4096, true);
        ChatModel plain = provider.modelFor(CACHING_MODEL, 0.7, 4096, false);

        assertThat(cached).isNotSameAs(plain);
        assertThat(provider.modelFor(CACHING_MODEL, 0.7, 4096, true)).isSameAs(cached);
        assertThat(((OpenAiChatModel) cached).defaultRequestParameters().customParameters()).isNotEmpty();
        assertThat(((OpenAiChatModel) plain).defaultRequestParameters().customParameters()).isNullOrEmpty();
    }

    private static Map<String, Object> customParametersOf(String model, String cacheWritePrice,
                                                          boolean cachePrompt) {
        ChatModel built = provider(model, cacheWritePrice).modelFor(model, 0.7, 4096, cachePrompt);
        return ((OpenAiChatModel) built).defaultRequestParameters().customParameters();
    }

    private static Lc4jChatModelProvider provider(String model, String cacheWritePrice) {
        AIConfigService configService = mock(AIConfigService.class);
        ModelCapabilityRegistry registry = mock(ModelCapabilityRegistry.class);
        when(configService.getDecryptedOpenRouterApiKey()).thenReturn("sk-or-v1-realkey");
        when(registry.getCapabilities(model)).thenReturn(ModelCapabilities.fromProviderMetadata(
                model, 200_000, List.of("tools", "structured_outputs"), cacheWritePrice));

        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(configService, registry);
        // @Value fields aren't injected under a plain constructor; the client builder
        // needs a valid (non-zero) duration.
        ReflectionTestUtils.setField(provider, "requestTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(provider, "maxRetries", 2);
        ReflectionTestUtils.setField(provider, "openRouterAppTitle", "Bvisionry");
        return provider;
    }
}
