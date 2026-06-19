package com.bvisionry.e2e;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * E2E-only beans that swap the live AI transport for a deterministic fake.
 *
 * <p>Because {@code com.bvisionry.config.AIConfig#lc4jChatModelProvider} is
 * {@code @Profile("!e2e & !mock")}, the provider below is the <em>only</em>
 * {@link Lc4jChatModelProvider} bean in the e2e context — no {@code @Primary}
 * needed. Every model resolves to a {@link FakeLangChainChatModel} sharing the
 * scripted-response registry, so the full evaluation pipeline runs deterministically
 * with no provider.
 */
@Configuration
@Profile("e2e")
public class E2eAiConfig {

    @Bean
    public FakeChatResponseRegistry fakeChatResponseRegistry() {
        return new FakeChatResponseRegistry();
    }

    @Bean
    public Lc4jChatModelProvider lc4jChatModelProvider(FakeChatResponseRegistry registry,
                                                       AIConfigService configService,
                                                       ModelCapabilityRegistry capabilityRegistry) {
        return new Lc4jChatModelProvider(configService, capabilityRegistry) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                return new FakeLangChainChatModel(registry);
            }
        };
    }
}
