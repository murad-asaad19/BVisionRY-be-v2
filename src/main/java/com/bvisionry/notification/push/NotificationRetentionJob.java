package com.bvisionry.notification.push;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounds growth of the notifications history table: rows older than the
 * retention window are deleted daily. Mirrors {@code AICallLogRetentionJob}'s
 * shape; a non-positive retention value disables the purge.
 */
// ponytail: single unbatched DELETE — notification volume is a few hundred
// rows/day; adopt the retention job's batched loop if this table ever gets hot.
@Component
@Slf4j
public class NotificationRetentionJob {

    private final UserNotificationRepository repository;
    private final int retentionDays;

    public NotificationRetentionJob(
            UserNotificationRepository repository,
            @Value("${bvisionry.notifications.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${bvisionry.notifications.retention.interval-ms:86400000}",
            initialDelayString = "${bvisionry.notifications.retention.initial-delay-ms:300000}")
    @SchedulerLock(name = "NotificationRetentionJob_purgeOld",
            lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeOld() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            long deleted = repository.deleteByCreatedAtBefore(
                    Instant.now().minus(Duration.ofDays(retentionDays)));
            if (deleted > 0) {
                log.info("NotificationRetentionJob: purged {} notification(s) older than {} day(s)",
                        deleted, retentionDays);
            }
        } catch (RuntimeException e) {
            log.error("NotificationRetentionJob purge failed: {}", e.getMessage(), e);
        }
    }
}
