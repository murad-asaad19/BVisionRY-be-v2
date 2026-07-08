package com.bvisionry.aiengine.resilience;

import com.bvisionry.common.exception.AIServiceException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Wraps AI provider calls in a circuit breaker + bulkhead so the engine stays
 * resilient regardless of which model/provider is configured.
 *
 * <ul>
 *   <li><b>Circuit breaker</b> — when the provider is failing or pathologically
 *       slow, the circuit opens and subsequent calls fail fast instead of every
 *       submission burning its retry budget against a degraded upstream
 *       (thundering herd). It half-opens after a cooldown to probe recovery.</li>
 *   <li><b>Bulkhead</b> — caps concurrent in-flight provider calls so a backlog
 *       of slow calls can't consume unbounded resources.</li>
 * </ul>
 *
 * <p>Crucially, {@link OutputGuardrailException} (the model produced invalid
 * <em>content</em> after repair retries) is <b>ignored</b> by the breaker: that's
 * a content problem, not a provider-health problem, so it must not trip the
 * circuit. Only transport/HTTP-level failures count toward opening it.
 */
@Component
@Slf4j
public class AiResilience {

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public AiResilience(
            MeterRegistry meterRegistry,
            @Value("${bvisionry.ai.cb.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${bvisionry.ai.cb.slow-call-rate-threshold:80}") float slowCallRateThreshold,
            @Value("${bvisionry.ai.cb.slow-call-duration-seconds:120}") long slowCallDurationSeconds,
            @Value("${bvisionry.ai.cb.wait-duration-open-seconds:30}") long waitDurationOpenSeconds,
            @Value("${bvisionry.ai.cb.sliding-window-size:20}") int slidingWindowSize,
            @Value("${bvisionry.ai.cb.minimum-calls:8}") int minimumCalls,
            @Value("${bvisionry.ai.bulkhead.max-concurrent:32}") int maxConcurrent,
            @Value("${bvisionry.ai.bulkhead.max-wait-millis:3000}") long bulkheadMaxWaitMillis) {

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                // Opens when >=80% of the sliding window exceeds slowCallDurationThreshold
                // — a brown-out signature — while tolerating a slow minority under mixed
                // load. At 100% the breaker could only trip if EVERY call were slow, so a
                // provider brown-out where most-but-not-all calls hang never opened it.
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(Duration.ofSeconds(slowCallDurationSeconds))
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationOpenSeconds))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .permittedNumberOfCallsInHalfOpenState(3)
                // Content failures (bad model output after repair) are not provider
                // outages — never let them open the circuit.
                .ignoreExceptions(OutputGuardrailException.class)
                .build();
        this.circuitBreaker = CircuitBreaker.of("ai-provider", cbConfig);

        // Size the bulkhead ABOVE the concurrent provider-call demand, and give it a short
        // wait window: pillar fan-out (16) + escalation budget (8) + summary calls on eval
        // threads (8) = 32. Without headroom + wait, brief LOCAL contention (the pool
        // momentarily full) was rejected immediately → AIServiceException → false
        // NEEDS_REVIEW that had nothing to do with provider health. A few seconds of
        // queueing lets transient bursts drain instead.
        BulkheadConfig bhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ofMillis(bulkheadMaxWaitMillis))
                .build();
        this.bulkhead = Bulkhead.of("ai-provider", bhConfig);

        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition t = event.getStateTransition();
            log.warn("AI circuit breaker transition: {} -> {}", t.getFromState(), t.getToState());
            meterRegistry.counter("bvisionry.ai.circuit_breaker_transition",
                    "to", t.getToState().name()).increment();
        });
    }

    /**
     * Run an AI call through the bulkhead + circuit breaker. Resilience rejections
     * (open circuit / bulkhead full) surface as {@link AIServiceException} so the
     * caller treats them like any other transport failure (isolated per pillar →
     * NEEDS_REVIEW). Other exceptions propagate unchanged.
     */
    public <T> T execute(Supplier<T> call) {
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new AIServiceException(
                    "AI provider is unavailable (circuit open after recent failures). Please retry shortly.", e);
        } catch (BulkheadFullException e) {
            throw new AIServiceException(
                    "AI provider concurrency limit reached. Please retry shortly.", e);
        }
    }

    /** Current circuit state — test-only observability. */
    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
