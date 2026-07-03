package com.bvisionry.publicassessment;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reclaims abandoned anonymous assessment sessions (F6/F42).
 *
 * <p>A public link reserves a response slot atomically at session-create (so
 * concurrent starts can't race past {@code maxResponses}). The flip side is that a
 * respondent who starts but never submits used to hold that slot forever — so honest
 * abandonment, or scripted session-create spam, could permanently brick a capped
 * link. This scheduled job deletes public submissions left IN_PROGRESS past a TTL
 * and releases their reserved slot, so the cap reflects real (active + completed)
 * responses and self-heals.
 *
 * <p>Only anonymous public sessions are touched (member submissions are managed via
 * assignments). Dependent answer rows are removed by the DB ON DELETE CASCADE that
 * the bulk delete relies on, which also cleans up orphaned answers.
 */
@Component
@Slf4j
public class PublicSubmissionReaper {

    private final SubmissionRepository submissionRepository;
    private final PublicAssessmentLinkRepository linkRepository;
    private final long ttlSeconds;
    private final int batchLimit;

    public PublicSubmissionReaper(
            SubmissionRepository submissionRepository,
            PublicAssessmentLinkRepository linkRepository,
            @Value("${bvisionry.public-assessment.abandoned-session-ttl-days:14}") int ttlDays,
            @Value("${bvisionry.public-assessment.abandoned-session-batch-limit:200}") int batchLimit) {
        this.submissionRepository = submissionRepository;
        this.linkRepository = linkRepository;
        this.ttlSeconds = (long) ttlDays * 86_400L;
        this.batchLimit = batchLimit;
    }

    // Hourly cadence; one bounded, status-guarded batch DELETE (<=200 rows) + slot
    // decrements in a single transaction completes in seconds — 5m ceiling is ample and
    // still far under the 1h interval, 1m floor covers clock skew between replicas.
    @Scheduled(
            fixedDelayString = "${bvisionry.public-assessment.abandoned-session.interval-ms:3600000}",
            initialDelayString = "${bvisionry.public-assessment.abandoned-session.initial-delay-ms:600000}")
    @SchedulerLock(name = "PublicSubmissionReaper_reapAbandonedSessions",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    @Transactional
    public void reapAbandonedSessions() {
        if (ttlSeconds <= 0) {
            return; // retention disabled
        }
        try {
            List<Object[]> stale = submissionRepository.findStaleInProgressPublicSessions(ttlSeconds, batchLimit);
            if (stale.isEmpty()) {
                return;
            }

            List<UUID> submissionIds = new ArrayList<>(stale.size());
            for (Object[] row : stale) {
                submissionIds.add((UUID) row[0]);
            }

            // Delete FIRST under a status-guarded native DELETE, then release a slot only
            // for the rows this instance actually deleted (the returned link ids). The
            // guard skips any session that became SUBMITTED since the SELECT (no TOCTOU
            // delete of just-submitted work); the row lock means a competing instance
            // deletes 0 of the same rows and returns no ids (no double-decrement).
            List<UUID> freedLinkIds = submissionRepository.deleteStaleInProgressReturningLinkIds(submissionIds);
            freedLinkIds.forEach(linkRepository::decrementResponseCount);

            log.info("PublicSubmissionReaper: reclaimed {} abandoned IN_PROGRESS public session(s), "
                    + "releasing their reserved response slots", freedLinkIds.size());
        } catch (RuntimeException e) {
            log.error("PublicSubmissionReaper sweep failed: {}", e.getMessage(), e);
        }
    }
}
