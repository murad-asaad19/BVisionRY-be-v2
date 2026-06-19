package com.bvisionry.config;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the AI subsystem's transport bean.
 *
 * <p>{@code @Profile("!e2e & !mock")} ensures this production provider does not
 * register when the e2e test source set ({@code com.bvisionry.e2e.E2eAiConfig}) or
 * the local {@code mock} profile ({@code com.bvisionry.config.mock.MockAiConfig}) is
 * active — so exactly one {@link Lc4jChatModelProvider} exists at runtime, no
 * {@code @Primary} disambiguation needed.
 */
@Configuration
public class AIConfig {

    @Bean
    @Profile("!e2e & !mock")
    public Lc4jChatModelProvider lc4jChatModelProvider(AIConfigService configService,
                                                       ModelCapabilityRegistry capabilityRegistry) {
        return new Lc4jChatModelProvider(configService, capabilityRegistry);
    }
}
