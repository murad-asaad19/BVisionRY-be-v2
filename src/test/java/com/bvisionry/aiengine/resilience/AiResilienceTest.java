package com.bvisionry.aiengine.resilience;

import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the provider resilience layer: transport failures open the circuit and
 * subsequent calls fail fast, while content (guardrail) failures never trip it.
 */
class AiResilienceTest {

    /** Small window so a few failures are enough to open the circuit in-test. */
    private AiResilience lowThreshold() {
        return new AiResilience(new SimpleMeterRegistry(), 50f, 120, 30, 4, 4, 16);
    }

    @Test
    void successPassesThrough() {
        assertThat(lowThreshold().execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void repeatedTransportFailures_openCircuitAndFailFast() {
        AiResilience resilience = lowThreshold();

        for (int i = 0; i < 4; i++) {
            try {
                resilience.execute(() -> { throw new RuntimeException("provider 503"); });
            } catch (RuntimeException ignored) {
                // expected — these failures fill the sliding window
            }
        }

        assertThat(resilience.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        // With the circuit open, even a would-succeed call is rejected fast as a
        // transport-style failure the caller can isolate per pillar.
        assertThatThrownBy(() -> resilience.execute(() -> "would-succeed"))
                .isInstanceOf(AIServiceException.class)
                .hasMessageContaining("circuit open");
    }

    @Test
    void guardrailFailures_doNotOpenCircuit() {
        AiResilience resilience = lowThreshold();

        for (int i = 0; i < 10; i++) {
            try {
                resilience.execute(() -> { throw new OutputGuardrailException("bad content"); });
            } catch (OutputGuardrailException ignored) {
                // expected — content failures must be ignored by the breaker
            }
        }

        // Ignored exceptions never register, so the circuit stays closed and calls flow.
        assertThat(resilience.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(resilience.execute(() -> "ok")).isEqualTo("ok");
    }
}
