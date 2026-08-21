package com.bvisionry.aiengine.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping tests for {@link ModelCapabilities#fromProviderMetadata} — the
 * model-agnostic capability detection — with no network or Spring context.
 */
class ModelCapabilitiesTest {

    @Test
    void mapsAllSupportedParameters() {
        ModelCapabilities caps = ModelCapabilities.fromProviderMetadata(
                "anthropic/claude-sonnet-4",
                200_000,
                List.of("tools", "response_format", "structured_outputs", "seed", "temperature"),
                null);

        assertThat(caps.modelId()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsJsonObject()).isTrue();
        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsSeed()).isTrue();
        assertThat(caps.contextLength()).isEqualTo(200_000);
    }

    @Test
    void isCaseInsensitiveAndTrims() {
        ModelCapabilities caps = ModelCapabilities.fromProviderMetadata(
                "x/y", 0, List.of("  Structured_Outputs ", "TOOLS"), null);

        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsJsonObject()).isFalse();
        assertThat(caps.supportsSeed()).isFalse();
    }

    @Test
    void emptyOrNullParametersYieldConservativeFlags() {
        ModelCapabilities fromNull = ModelCapabilities.fromProviderMetadata("m", 123, null, null);
        ModelCapabilities fromEmpty = ModelCapabilities.fromProviderMetadata("m", 123, List.of(), null);

        for (ModelCapabilities caps : List.of(fromNull, fromEmpty)) {
            assertThat(caps.supportsStructuredOutputs()).isFalse();
            assertThat(caps.supportsJsonObject()).isFalse();
            assertThat(caps.supportsTools()).isFalse();
            assertThat(caps.supportsSeed()).isFalse();
            assertThat(caps.contextLength()).isEqualTo(123);
        }
    }

    /**
     * The pricing signal behind {@code supportsExplicitPromptCaching}: a quoted
     * cache-WRITE price means caching there has to be asked for. The strings are
     * verbatim from OpenRouter's {@code /models} — Sonnet 4 prices writes, GPT-4o
     * prices only reads (it caches automatically and must not be sent a directive).
     */
    @Test
    void explicitPromptCachingFollowsTheCacheWritePrice() {
        assertThat(ModelCapabilities.fromProviderMetadata(
                        "anthropic/claude-sonnet-4", 200_000, List.of("tools"), "0.00000375")
                .supportsExplicitPromptCaching()).isTrue();

        assertThat(ModelCapabilities.fromProviderMetadata("openai/gpt-4o", 128_000, List.of("tools"), null)
                .supportsExplicitPromptCaching()).isFalse();
    }

    @Test
    void nonPositiveOrUnparseableCacheWritePriceDisablesCaching() {
        for (String price : List.of("0", "0.0", "-0.1", "", "   ", "free")) {
            assertThat(ModelCapabilities.fromProviderMetadata("m", 0, List.of("tools"), price)
                    .supportsExplicitPromptCaching())
                    .as("price %s", price)
                    .isFalse();
        }
    }

    @Test
    void conservativeDisablesEverything() {
        ModelCapabilities caps = ModelCapabilities.conservative("unknown/model");

        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonObject()).isFalse();
        assertThat(caps.supportsTools()).isFalse();
        assertThat(caps.supportsSeed()).isFalse();
        assertThat(caps.supportsExplicitPromptCaching()).isFalse();
        assertThat(caps.contextLength()).isZero();
    }
}
