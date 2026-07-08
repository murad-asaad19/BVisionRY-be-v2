package com.bvisionry.aicalllog.service;

import com.bvisionry.aicalllog.repository.AICallLogRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounds long-term growth of {@code ai_call_logs} (F41): the table accrues several
 * rows per submission and, without a retention policy, grows without limit on the
 * same Postgres instance serving the hot path. This scheduled purge deletes rows
 * older than the configured retention window. Payload-size growth per row is bounded
 * separately in {@link AICallLogService}; this bounds row count over time.
 */
@Component
@Slf4j
public class AICallLogRetentionJob {

    private final AICallLogRepository repository;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;

    public AICallLogRetentionJob(
            AICallLogRepository repository,
            @Value("${bvisionry.ai-call-log.retention-days:90}") int retentionDays,
            @Value("${bvisionry.ai-call-log.retention.batch-size:1000}") int batchSize,
            @Value("${bvisionry.ai-call-log.retention.max-batches:10000}") int maxBatches) {
        this.repository = repository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /**
     * Runs daily (and ~5 min after boot). A non-positive retention value disables
     * the purge so an operator can opt into keeping everything. Fully guarded so a
     * failed purge never kills the scheduler.
     *
     * <p>Deletes in bounded batches of {@code batch-size} rows, each in its own
     * transaction, so a large backlog never holds a single long-running DELETE (and
     * its locks) against the hot-path table. Loops until a batch deletes fewer than
     * {@code batch-size} rows — i.e. nothing older than the cutoff remains — capped at
     * {@code max-batches} iterations as a safety bound against unbounded looping.
     */
    // Daily cadence, but the first run can clear a large backlog across many bounded
    // batches, so the ceiling is set to 1h (overriding the 30m config default) to cover
    // that worst case while staying far under the 24h interval; 1m floor absorbs skew.
    @Scheduled(fixedDelayString = "${bvisionry.ai-call-log.retention.interval-ms:86400000}",
            initialDelayString = "${bvisionry.ai-call-log.retention.initial-delay-ms:300000}")
    @SchedulerLock(name = "AICallLogRetentionJob_purgeOldLogs",
            lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeOldLogs() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            long totalDeleted = 0;
            int batches = 0;
            int deleted;
            do {
                deleted = repository.deleteBatchByCalledAtBefore(cutoff, batchSize);
                totalDeleted += deleted;
                batches++;
            } while (deleted == batchSize && batches < maxBatches);

            if (batches >= maxBatches && deleted == batchSize) {
                log.warn("AICallLogRetentionJob: hit max-batches cap ({}) with rows still older "
                                + "than {} day(s) remaining; will continue on the next run",
                        maxBatches, retentionDays);
            }
            if (totalDeleted > 0) {
                log.info("AICallLogRetentionJob: purged {} ai_call_logs row(s) older than {} day(s) "
                                + "across {} batch(es)",
                        totalDeleted, retentionDays, batches);
            }
        } catch (RuntimeException e) {
            log.error("AICallLogRetentionJob purge failed: {}", e.getMessage(), e);
        }
    }
}
