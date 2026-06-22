package com.bvisionry.publicassessment;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(
            fixedDelayString = "${bvisionry.public-assessment.abandoned-session.interval-ms:3600000}",
            initialDelayString = "${bvisionry.public-assessment.abandoned-session.initial-delay-ms:600000}")
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
                UUID submissionId = (UUID) row[0];
                UUID linkId = (UUID) row[1];
                submissionIds.add(submissionId);
                linkRepository.decrementResponseCount(linkId);
            }
            int deleted = submissionRepository.deleteAllByIdIn(submissionIds);
            log.info("PublicSubmissionReaper: reclaimed {} abandoned IN_PROGRESS public session(s), "
                    + "releasing their reserved response slots", deleted);
        } catch (RuntimeException e) {
            log.error("PublicSubmissionReaper sweep failed: {}", e.getMessage(), e);
        }
    }
}
