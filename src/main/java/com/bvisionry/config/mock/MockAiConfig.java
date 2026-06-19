package com.bvisionry.config.mock;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Activates a static-mock AI transport under the {@code mock} Spring profile so the
 * assessment-evaluation flow works locally with no live model / API key.
 *
 * <p>It overrides {@link Lc4jChatModelProvider#modelFor} — the single seam between
 * the engine and the network — so every model resolves to a
 * {@link MockLangChainChatModel} returning canned, schema-valid JSON. Because
 * {@code AIConfig#lc4jChatModelProvider} is {@code @Profile("!e2e & !mock")},
 * exactly one provider bean exists at runtime. Activate with e.g.
 * {@code SPRING_PROFILES_ACTIVE=dev,mock}.
 */
@Configuration
@Profile("mock")
public class MockAiConfig {

    @Bean
    public Lc4jChatModelProvider lc4jChatModelProvider(AIConfigService configService,
                                                       ModelCapabilityRegistry capabilityRegistry) {
        return new Lc4jChatModelProvider(configService, capabilityRegistry) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                return new MockLangChainChatModel();
            }
        };
    }
}
