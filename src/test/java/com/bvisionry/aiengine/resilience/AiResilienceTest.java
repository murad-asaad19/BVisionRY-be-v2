package com.bvisionry.aiengine.resilience;

import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the provider resilience layer: transport failures open the circuit and
 * subsequent calls fail fast, while content (guardrail) failures never trip it.
 */
class AiResilienceTest {

    /** Small window so a few failures are enough to open the circuit in-test. */
    private AiResilience lowThreshold() {
        return new AiResilience(new SimpleMeterRegistry(), 50f, 120, 30, 4, 4, 16, 3000);
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

    /**
     * H2: brief LOCAL contention must QUEUE within the wait window and then succeed,
     * not be rejected immediately as a fake provider failure. With a size-1 bulkhead and
     * a 3s wait, a second call submitted while the slot is held waits for it to free up
     * and completes — no {@link AIServiceException}.
     */
    @Test
    void briefContention_queuesAndSucceeds_insteadOfFailing() throws InterruptedException {
        // bulkhead size 1, 3s max wait.
        AiResilience resilience = new AiResilience(new SimpleMeterRegistry(), 50f, 120, 30, 20, 8, 1, 3000);

        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> resilience.execute(() -> {
            holderStarted.countDown();
            try {
                releaseHolder.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "held";
        }));
        holder.start();
        assertThat(holderStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Free the slot shortly after the second call begins waiting (well inside 3s).
        AtomicBoolean queuedCallRan = new AtomicBoolean(false);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            releaseHolder.countDown();
        });
        releaser.start();

        // This call finds the only slot taken; it must WAIT (not fail-fast) and then run.
        String result = resilience.execute(() -> {
            queuedCallRan.set(true);
            return "queued-then-ran";
        });

        holder.join(2000);
        releaser.join(2000);
        assertThat(queuedCallRan).isTrue();
        assertThat(result).isEqualTo("queued-then-ran");
    }
}
