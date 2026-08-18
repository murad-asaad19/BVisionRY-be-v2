package com.bvisionry.config;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.config.mock.MockAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The AI transport must never reach a real provider from a local run.
 *
 * <p>This is a MONEY guard, not a style one: the transport was opt-in mock, so
 * `./mvnw spring-boot:run` — the loop the README recommends — billed OpenRouter
 * for every evaluation, narrative and cohort summary. Two things have to hold
 * for that to stay fixed, and each is asserted here rather than assumed:
 *
 * <ol>
 *   <li>the LOCAL profile files actually set {@code bvisionry.ai.mock.enabled=true},
 *       and the shipped default stays {@code false} so a real deployment is not
 *       silently served canned output;
 *   <li>the two {@code @ConditionalOnProperty} arms are genuinely complementary —
 *       exactly one {@link Lc4jChatModelProvider} for {@code true} and for
 *       {@code false}, and NONE for a value that is neither, so an unreadable
 *       override fails the boot rather than falling through to the transport
 *       that bills;
 *   <li>a {@code prod} boot REFUSES the mock outright. That is the failure this
 *       change made newly reachable, and the only one here that is invisible:
 *       the mock answers every call successfully, so a mocked production looks
 *       entirely healthy while serving customers canned text.
 * </ol>
 */
class AiTransportDefaultTest {

    private static final String KEY = "bvisionry.ai.mock.enabled";

    private static Properties profile(String name) throws IOException {
        Properties props = new Properties();
        try (var in = new ClassPathResource(name).getInputStream()) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    /* ------------------------------------------------------ the properties */

    @Test
    void everyLocalProfileMocksTheTransport() throws IOException {
        // dev is the DEFAULT profile (application.properties), so this one line
        // is what makes the safe path automatic rather than something to
        // remember. local and mock are the other two a laptop ever runs.
        for (String file : new String[] {
                "application-dev.properties",
                "application-local.properties",
                "application-mock.properties",
        }) {
            assertThat(profile(file).getProperty(KEY))
                    .as("%s must mock the AI transport", file)
                    .isEqualTo("true");
        }
    }

    @Test
    void theShippedDefaultStillReachesTheRealProvider() throws IOException {
        // The inverse failure: a deployment that inherited the local default
        // would serve canned AI output to paying customers and look healthy.
        assertThat(profile("application.properties").getProperty(KEY))
                .as("the base profile must not mock")
                .isEqualTo("false");
        assertThat(profile("application-prod.properties").getProperty(KEY))
                .as("prod must not override the base default")
                .isNull();
    }

    /* ---------------------------------------------------------- the wiring */

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AIConfig.class, MockAiConfig.class)
                .withBean(AIConfigService.class, () -> mock(AIConfigService.class))
                .withBean(ModelCapabilityRegistry.class, () -> mock(ModelCapabilityRegistry.class));
    }

    /** The mock arm overrides `modelFor`; the real arm is the plain class. */
    private static boolean isMockTransport(Lc4jChatModelProvider provider) {
        return provider.modelFor("any/model", 0, 1) instanceof MockLangChainChatModel;
    }

    @Test
    void mockEnabledGivesTheStaticTransport() {
        runner().withPropertyValues(KEY + "=true").run(context -> {
            assertThat(context).hasSingleBean(Lc4jChatModelProvider.class);
            assertThat(isMockTransport(context.getBean(Lc4jChatModelProvider.class))).isTrue();
        });
    }

    @Test
    void mockDisabledGivesTheRealTransport() {
        runner().withPropertyValues(KEY + "=false").run(context -> {
            assertThat(context).hasSingleBean(Lc4jChatModelProvider.class);
            assertThat(context.getBean(Lc4jChatModelProvider.class).getClass())
                    .as("the real provider is the plain class, not the mock subclass")
                    .isEqualTo(Lc4jChatModelProvider.class);
        });
    }

    @Test
    void anUnreadableValueWiresNoTransportAtAll() {
        // Neither arm matches, so the context has no transport and anything
        // needing one fails at REFRESH. Both alternatives are worse: defaulting
        // to the mock would serve canned output to customers, and defaulting to
        // the real provider would bill on a typo. application.properties always
        // ships the key, so no real runtime reaches this branch — it exists so
        // that a broken override cannot pick a transport by accident.
        for (String bad : new String[] {"", "yes", "1", "TRUE-ish"}) {
            runner().withPropertyValues(KEY + "=" + bad).run(context ->
                    assertThat(context).doesNotHaveBean(Lc4jChatModelProvider.class));
        }
        runner().run(context ->
                assertThat(context).doesNotHaveBean(Lc4jChatModelProvider.class));
    }

    @Test
    void theProdProfileRefusesToBootMocked() {
        // The one failure this whole change makes newly reachable: prod is
        // selected by profile, but the mock is now selected by a PROPERTY, and
        // `application-mock.properties` beats `application-prod.properties`
        // because profile-specific files win. A `prod,mock` boot, or a stray
        // BVISIONRY_AI_MOCK_ENABLED=true in a deploy dashboard, would serve
        // canned narratives from a deployment whose every health check is green.
        StartupSafetyValidator validator = prodValidator(true);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bvisionry.ai.mock.enabled=true");

        assertThatCode(() -> prodValidator(false).afterPropertiesSet())
                .as("the same prod config with a live transport still boots")
                .doesNotThrowAnyException();
    }

    /** A prod-profile validator whose every OTHER guard already passes. */
    private static StartupSafetyValidator prodValidator(boolean aiMockEnabled) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        return new StartupSafetyValidator(
                env, true, "a-strong-unique-jwt-secret-of-more-than-256-bits-in-length",
                "a-strong-unique-encryption-key", "a-strong-unique-proxy-secret",
                "a-real-vapid-private-key", "a-real-minio-secret", aiMockEnabled);
    }
}
