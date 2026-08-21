package com.bvisionry.common.errortracking;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounds long-term growth of {@code error_events}, cloning
 * {@code AICallLogRetentionJob}. Per-row size is bounded by
 * {@link ErrorEventRecorder}'s truncation and the DTO's {@code @Size} caps; nothing
 * bounds row COUNT, and a crash loop writes rows as fast as the rate limiter allows
 * — on the same Postgres instance serving the hot path. This bounds it over time.
 */
@Component
@Slf4j
public class ErrorEventRetentionJob {

    private final ErrorEventRepository repository;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;

    public ErrorEventRetentionJob(
            ErrorEventRepository repository,
            @Value("${bvisionry.error-events.retention-days:90}") int retentionDays,
            @Value("${bvisionry.error-events.retention.batch-size:1000}") int batchSize,
            @Value("${bvisionry.error-events.retention.max-batches:10000}") int maxBatches) {
        this.repository = repository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /**
     * Daily, ~5 min after boot. A non-positive retention value disables the purge so
     * an operator can opt into keeping everything. Fully guarded so a failed purge
     * never kills the scheduler.
     */
    @Scheduled(fixedDelayString = "${bvisionry.error-events.retention.interval-ms:86400000}",
            initialDelayString = "${bvisionry.error-events.retention.initial-delay-ms:300000}")
    @SchedulerLock(name = "ErrorEventRetentionJob_purgeOldEvents",
            lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeOldEvents() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            long totalDeleted = 0;
            int batches = 0;
            int deleted;
            do {
                deleted = repository.deleteBatchByCreatedAtBefore(cutoff, batchSize);
                totalDeleted += deleted;
                batches++;
            } while (deleted == batchSize && batches < maxBatches);

            if (totalDeleted > 0) {
                log.info("ErrorEventRetentionJob: purged {} error_events row(s) older than {} day(s) "
                                + "across {} batch(es)",
                        totalDeleted, retentionDays, batches);
            }
        } catch (RuntimeException e) {
            log.error("ErrorEventRetentionJob purge failed: {}", e.getMessage(), e);
        }
    }
}
