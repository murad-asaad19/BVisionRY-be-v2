package com.bvisionry.config;

import io.micrometer.observation.ObservationRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

import javax.sql.DataSource;

/**
 * Distributed-lock wiring for the app's {@code @Scheduled} reaper/expiry jobs.
 *
 * <p>Without this, every replica runs every scheduled job on every tick — harmless
 * on a single instance, but the moment Railway scales out it means duplicate
 * trial-expiry processing and reap DELETEs racing across instances. ShedLock lets
 * each {@code @SchedulerLock}-annotated method acquire a named row lock so at most
 * one instance executes it per tick; the others skip that tick.
 *
 * <p><b>JDBC (Postgres) provider, deliberately not Redis.</b> The database is a hard
 * dependency of the app, whereas Redis is allowed to degrade (it's excluded from the
 * health checks and the rate limiter falls back to in-memory when it's down). Anchoring
 * the lock to the DB means scheduler coordination can never be broken by a Redis outage.
 *
 * <p><b>{@code usingDbTime()}</b> makes ShedLock evaluate lock expiry against the
 * database clock instead of each JVM's wall clock, so replicas with skewed clocks can't
 * both consider a lock expired and double-run a job. {@code defaultLockAtMostFor} is the
 * safety ceiling applied when a job doesn't set its own {@code lockAtMostFor} — if an
 * instance dies mid-run, the lock self-releases after this long so the next tick can
 * proceed. Every job here sets an explicit per-job value sized to its own worst-case
 * runtime; this default is only a backstop.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    /**
     * Backs the locks with the {@code shedlock} table created in V114. Construction
     * touches no rows; ShedLock only reads/writes the table when a locked job fires.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    /**
     * Turns on Spring's own {@code @Scheduled} instrumentation, so every
     * execution becomes a timed observation tagged SUCCESS or ERROR and is
     * exported through the actuator's existing Prometheus endpoint. The registry
     * is opt-in by design (framework docs: "Observability > @Scheduled tasks
     * instrumentation"), so it has to be handed to the registrar explicitly.
     *
     * <p>This is for METRICS ONLY. It is deliberately not what
     * {@link com.bvisionry.common.scheduling.ScheduledJobMonitor} reads — that
     * uses {@code Task.getLastExecutionOutcome()}, which Spring maintains whether
     * or not this line exists. Keeping the health verdict independent of the
     * observation pipeline means the in-box signal cannot be switched off by an
     * unrelated observability change, and it needs nobody to have provisioned a
     * scrape.
     *
     * <p>Lives here rather than in its own config class because this is already
     * the file that owns scheduler-wide wiring; the jobs it instruments are the
     * same ones {@code @EnableSchedulerLock} above coordinates.
     */
    @Bean
    public SchedulingConfigurer scheduledTaskObservations(ObservationRegistry observationRegistry) {
        return taskRegistrar -> taskRegistrar.setObservationRegistry(observationRegistry);
    }
}
