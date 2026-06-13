package com.bvisionry.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberScoreRow(
        UUID userId,
        UUID submissionId,
        String memberName,
        String memberEmail,
        BigDecimal overallScore,
        String submissionStatus,
        Instant evaluatedAt,
        List<PillarScoreSummary> pillarScores,
        UUID assignmentId,
        Instant deadline,
        Instant deadlineOverride
) {}
