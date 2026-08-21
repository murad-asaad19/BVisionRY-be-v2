package com.bvisionry.config;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the AI subsystem's transport bean — the one that actually reaches
 * OpenRouter and BILLS for every call.
 *
 * <p>Which transport is live is a PROPERTY, not a profile, for the same reason
 * the mail transport is ({@code bvisionry.mail.transport}): the choice has to be
 * settable per environment without anyone having to remember to append a profile
 * name. {@code bvisionry.ai.mock.enabled} is {@code false} in
 * {@code application.properties} and {@code true} in every local profile
 * ({@code dev}, {@code local}, {@code mock}), so a laptop cannot spend money on a
 * real model by accident — which is what happened while it was opt-in.
 *
 * <p>Both arms are EXPLICIT — no {@code matchIfMissing}. {@code
 * application.properties} always ships the key, so every real context resolves
 * it to {@code true} or {@code false} and one arm matches; a value that is
 * neither (empty, {@code yes}, a typo) now matches NOTHING and the context
 * fails to start on the missing bean. That is deliberate and matches this
 * codebase's fail-closed style: falling through an unreadable value into the
 * billing transport is the one outcome worth refusing. It also mirrors the
 * mail transport, where the free local sender carries the permissive default
 * and the billing one is opt-in.
 *
 * <p>{@code @Profile("!e2e")} keeps this out of the e2e context, where {@code
 * com.bvisionry.e2e.E2eAiConfig} supplies a scripted fake. Exactly one {@link
 * Lc4jChatModelProvider} exists at runtime, so no {@code @Primary}
 * disambiguation is needed.
 */
@Slf4j
@Configuration
public class AIConfig {

    @Bean
    @Profile("!e2e")
    @ConditionalOnProperty(name = "bvisionry.ai.mock.enabled", havingValue = "false")
    public Lc4jChatModelProvider lc4jChatModelProvider(AIConfigService configService,
                                                       ModelCapabilityRegistry capabilityRegistry) {
        // The mock says so loudly; so must this one. A log that only announces
        // the harmless transport cannot be used to answer "which one answered",
        // which is the entire point of announcing it.
        log.info("AI transport: LIVE (OpenRouter). Provider calls will be made and BILLED.");
        return new Lc4jChatModelProvider(configService, capabilityRegistry);
    }
}
