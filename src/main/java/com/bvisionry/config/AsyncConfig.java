package com.bvisionry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Top-level submission evaluation pool. One thread per concurrent submission;
     * each task fans out per-pillar work onto {@link #pillarExecutor()}, never back
     * onto this pool. Keeping the two pools separate is what prevents the
     * fork-join deadlock that would otherwise occur once {@code maxPoolSize}
     * submissions are in flight (every parent thread blocked on
     * {@code CompletableFuture.join} waiting for a child task that can never
     * be scheduled because the pool is full).
     */
    @Bean(name = "evaluationExecutor")
    public Executor evaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("eval-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for the per-pillar fan-out inside a single submission.
     * Sized larger than {@link #evaluationExecutor()} because each parent
     * submission spawns N pillar tasks and blocks on their {@code join()} —
     * if pillar tasks shared the parent's pool, the parents would starve
     * the children and the whole pipeline would deadlock under load.
     */
    @Bean(name = "pillarExecutor")
    public Executor pillarExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("pillar-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for fire-and-forget AI call logging. Separate from the
     * evaluation pool so a flood of log writes can never starve real work.
     * Queue is generous since audit writes are backpressure-tolerant.
     */
    @Bean(name = "aiLogExecutor")
    public Executor aiLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-log-");
        executor.initialize();
        return executor;
    }

    /**
     * Bounded pool for transactional/notification email sends triggered via
     * {@code @Async("emailExecutor")}. Decoupled from the default Spring
     * executor so a slow SMTP / Resend endpoint can't block evaluation,
     * audit logging, or HTTP threads. Queue capacity is large enough to
     * absorb a burst of trial-ending notifications without rejecting work.
     */
    @Bean(name = "emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }
}
