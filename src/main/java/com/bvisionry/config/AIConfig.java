package com.bvisionry.config;

import com.bvisionry.aiconfig.service.AIConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the AI subsystem's beans.
 *
 * <p>{@code @Profile("!e2e & !mock")} ensures this production factory does not
 * register when the e2e test source set ({@code com.bvisionry.e2e.E2eAiConfig}) or
 * the local {@code mock} profile ({@code com.bvisionry.config.mock.MockAiConfig}) is
 * active — so exactly one {@link AIChatModelFactory} exists at runtime, no
 * {@code @Primary} disambiguation needed.
 */
@Configuration
public class AIConfig {

    @Bean
    @Profile("!e2e & !mock")
    public AIChatModelFactory aiChatModelFactory(AIConfigService configService) {
        return new AIChatModelFactory(configService);
    }
}
