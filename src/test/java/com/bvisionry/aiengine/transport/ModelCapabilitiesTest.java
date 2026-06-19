package com.bvisionry.aiengine.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping tests for {@link ModelCapabilities#fromSupportedParameters} — the
 * model-agnostic capability detection — with no network or Spring context.
 */
class ModelCapabilitiesTest {

    @Test
    void mapsAllSupportedParameters() {
        ModelCapabilities caps = ModelCapabilities.fromSupportedParameters(
                "anthropic/claude-sonnet-4",
                200_000,
                List.of("tools", "response_format", "structured_outputs", "seed", "temperature"));

        assertThat(caps.modelId()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsJsonObject()).isTrue();
        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsSeed()).isTrue();
        assertThat(caps.contextLength()).isEqualTo(200_000);
    }

    @Test
    void isCaseInsensitiveAndTrims() {
        ModelCapabilities caps = ModelCapabilities.fromSupportedParameters(
                "x/y", 0, List.of("  Structured_Outputs ", "TOOLS"));

        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsJsonObject()).isFalse();
        assertThat(caps.supportsSeed()).isFalse();
    }

    @Test
    void emptyOrNullParametersYieldConservativeFlags() {
        ModelCapabilities fromNull = ModelCapabilities.fromSupportedParameters("m", 123, null);
        ModelCapabilities fromEmpty = ModelCapabilities.fromSupportedParameters("m", 123, List.of());

        for (ModelCapabilities caps : List.of(fromNull, fromEmpty)) {
            assertThat(caps.supportsStructuredOutputs()).isFalse();
            assertThat(caps.supportsJsonObject()).isFalse();
            assertThat(caps.supportsTools()).isFalse();
            assertThat(caps.supportsSeed()).isFalse();
            assertThat(caps.contextLength()).isEqualTo(123);
        }
    }

    @Test
    void conservativeDisablesEverything() {
        ModelCapabilities caps = ModelCapabilities.conservative("unknown/model");

        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonObject()).isFalse();
        assertThat(caps.supportsTools()).isFalse();
        assertThat(caps.supportsSeed()).isFalse();
        assertThat(caps.contextLength()).isZero();
    }
}
