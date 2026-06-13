package com.bvisionry.e2e;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.config.AIChatModelFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * E2E-only beans that swap the live AI provider for a deterministic fake.
 *
 * <p>Because {@code com.bvisionry.config.AIConfig#aiChatModelFactory} is annotated
 * {@code @Profile("!e2e")}, the factory below is the <em>only</em>
 * {@link AIChatModelFactory} bean in the e2e application context — no {@code @Primary}
 * needed, no dangling production instance.
 */
@Configuration
@Profile("e2e")
public class E2eAiConfig {

    @Bean
    public FakeChatResponseRegistry fakeChatResponseRegistry() {
        return new FakeChatResponseRegistry();
    }

    @Bean
    public AIChatModelFactory aiChatModelFactory(FakeChatResponseRegistry registry,
                                                  AIConfigService configService) {
        return new AIChatModelFactory(configService) {
            @Override
            public ChatModel create() {
                return new FakeChatModel(registry);
            }
        };
    }
}
