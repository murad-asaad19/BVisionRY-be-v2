package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bvisionry.coaching.repository.CoachingReadRepository.QueueRow;

/**
 * The coach's review queue (redesign spec §2.2): every SUBMITTED exercise
 * across their visible founders, oldest first — the list is the priority
 * order. Each row deep-links to the existing review screen via
 * {@code assignmentId}.
 */
public record CoachReviewQueueResponse(List<CoachQueueItem> items) {

    /** §7b: {@code submittedAt} is the waiting-since stamp. */
    public record CoachQueueItem(
            UUID assignmentId,
            UUID founderId,
            String founderName,
            String exerciseName,
            Instant submittedAt,
            /** True when this copy came back after "request changes". */
            boolean resubmitted,
            /** §7b: when changes were last requested; null on a first submission. */
            Instant changesRequestedAt,
            /**
             * Spec §4 quality tag, label snapshot — present only on a copy that
             * was tagged on an earlier pass and has come back for re-review.
             * Metadata: it never orders or filters the queue.
             */
            String qualityTagLabel) {

        public static CoachQueueItem from(QueueRow row) {
            return new CoachQueueItem(row.assignmentId(), row.founderId(), row.founderName(),
                    row.exerciseName(),
                    row.submittedAt() == null ? null : row.submittedAt().toInstant(),
                    row.changesRequestedAt() != null,
                    row.changesRequestedAt() == null ? null : row.changesRequestedAt().toInstant(),
                    row.qualityTagLabel());
        }
    }
}
