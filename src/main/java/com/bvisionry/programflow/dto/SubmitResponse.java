package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Submission receipt. {@code pointsEarned} is 0 on re-submits. */
public record SubmitResponse(
        int pointsEarned,
        OffsetDateTime submittedAt,
        int answered,
        int answerable,
        UUID nextTaskId,
        String nextTaskName) {
}
