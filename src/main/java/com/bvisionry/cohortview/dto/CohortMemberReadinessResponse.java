package com.bvisionry.cohortview.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The member-in-cohort screen's readiness slice: the member's latest evaluated
 * sitting ON THIS COHORT'S OWN INSTRUMENTS (never the global latest — an
 * unrelated instrument's score must not stand in for the cohort's baseline),
 * with its pillar rows for the Growth tab table. All fields null / empty when
 * the cohort has no assessment tasks or the member never sat one of them.
 */
public record CohortMemberReadinessResponse(
        BigDecimal friLatest,
        /** {@code friLatest} minus the previous sitting on the same instruments. */
        BigDecimal friDelta,
        Instant evaluatedAt,
        List<ReadinessPillar> pillarScores) {

    /** Field names mirror the founder profile's pillar row so the web reuses one type. */
    public record ReadinessPillar(
            String pillarName,
            BigDecimal scorePercentage,
            String maturityLabel,
            Instant evaluatedAt) {}
}
