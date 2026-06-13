package com.bvisionry.config.mock;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.config.AIChatModelFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Activates a static-mock AI provider under the {@code mock} Spring profile so the
 * assessment-evaluation flow works locally with no live model / API key.
 *
 * <p>It overrides {@link AIChatModelFactory#create()} — the single seam between the
 * prompt-building layer and the network — so all three call types (pillar, overall
 * summary, team insight) are intercepted without touching {@code OpenRouterChatService}
 * or any caller. This mirrors the test-only {@code com.bvisionry.e2e.E2eAiConfig}.
 *
 * <p>Because {@code AIConfig#aiChatModelFactory} is {@code @Profile("!e2e & !mock")},
 * exactly one {@link AIChatModelFactory} bean exists at runtime — no {@code @Primary}.
 * Activate with e.g. {@code SPRING_PROFILES_ACTIVE=dev,mock}.
 */
@Configuration
@Profile("mock")
public class MockAiConfig {

    @Bean
    public AIChatModelFactory aiChatModelFactory(AIConfigService configService) {
        return new AIChatModelFactory(configService) {
            @Override
            public ChatModel create() {
                return new MockChatModel();
            }
        };
    }
}
