package com.bvisionry.evaluation.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload for the admin unlock/relock endpoints and the
 * "current unlock state" read.
 */
public record PillarUnlockSummary(
        UUID submissionId,
        SubmissionStatus status,
        List<UnlockedPillar> unlockedPillars
) {
    public record UnlockedPillar(
            UUID pillarId,
            String pillarName,
            Instant unlockedAt,
            UUID unlockedByAdminId,
            String reason
    ) {}
}
