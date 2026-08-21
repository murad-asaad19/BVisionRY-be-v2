package com.bvisionry.config.mock;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The static-mock AI transport: every model resolves to a
 * {@link MockLangChainChatModel} returning canned, schema-valid JSON, so
 * evaluation, shift narratives and the growth summaries all run locally with no
 * model key, no network and no spend.
 *
 * <p>This is the DEFAULT locally. It overrides {@link Lc4jChatModelProvider#modelFor}
 * — the single seam between the engine and the network — and is selected by
 * {@code bvisionry.ai.mock.enabled=true}, which the {@code dev}, {@code local} and
 * {@code mock} profiles all set. {@code AIConfig}'s real provider takes the
 * complementary {@code havingValue = "false"}, so exactly one bean exists; the
 * {@code !e2e} guard leaves the e2e context to {@code E2eAiConfig}'s scripted fake.
 *
 * <p>To reach the live provider from a local run — the deliberate, one-off case —
 * set {@code BVISIONRY_AI_MOCK_ENABLED=false} for that run.
 */
@Slf4j
@Configuration
public class MockAiConfig {

    @Bean
    @Profile("!e2e")
    @ConditionalOnProperty(name = "bvisionry.ai.mock.enabled", havingValue = "true")
    public Lc4jChatModelProvider lc4jChatModelProvider(AIConfigService configService,
                                                       ModelCapabilityRegistry capabilityRegistry) {
        // Loud on purpose. The failure this replaces was silent spend; the
        // opposite failure — reading canned output as a real evaluation — is
        // only avoidable if the log says which transport answered.
        log.warn("AI transport: MOCK (bvisionry.ai.mock.enabled=true). "
                + "No provider calls will be made and every AI result is canned.");
        return new Lc4jChatModelProvider(configService, capabilityRegistry) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens,
                                      boolean cachePrompt) {
                return new MockLangChainChatModel();
            }
        };
    }
}
