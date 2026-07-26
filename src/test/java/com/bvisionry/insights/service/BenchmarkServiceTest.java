package com.bvisionry.insights.service;

import com.bvisionry.insights.BenchmarkReadRepository.PillarRef;
import com.bvisionry.insights.BenchmarkReadRepository.SegmentRow;
import com.bvisionry.insights.dto.BenchmarkResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The merge's sufficiency mapping, boundary-pinned: 29 is "insufficient data",
 * 30 is a number, and the platform's insufficient state carries no count.
 */
class BenchmarkServiceTest {

    private static final UUID PIPELINE = UUID.randomUUID();
    private static final UUID PILLAR = UUID.randomUUID();
    private static final List<PillarRef> AXIS = List.of(new PillarRef(PILLAR, "Vision"));

    private static SegmentRow row(int n, Double mean) {
        BigDecimal value = mean == null ? null : BigDecimal.valueOf(mean);
        return new SegmentRow(PILLAR, n, value, value, value);
    }

    @Test
    void twentyNineIsInsufficient_thirtyIsANumber() {
        // The SQL hands back NULL aggregates below the floor and values at it.
        BenchmarkResponse at29 = BenchmarkService.assemble(PIPELINE, null, null, AXIS,
                null, Map.of(PILLAR, row(29, null)), Map.of());
        assertThat(at29.pillars().get(0).org().sufficient()).isFalse();
        assertThat(at29.pillars().get(0).org().sampleSize()).isEqualTo(29);
        assertThat(at29.pillars().get(0).org().mean()).isNull();

        BenchmarkResponse at30 = BenchmarkService.assemble(PIPELINE, null, null, AXIS,
                null, Map.of(PILLAR, row(30, 62.5)), Map.of(PILLAR, row(30, 62.5)));
        assertThat(at30.pillars().get(0).org().sufficient()).isTrue();
        assertThat(at30.pillars().get(0).org().mean()).isEqualByComparingTo("62.5");
        assertThat(at30.pillars().get(0).platform().sufficient()).isTrue();
        assertThat(at30.pillars().get(0).platform().sampleSize()).isEqualTo(30);
    }

    @Test
    void absentRowsDegradeHonestly() {
        BenchmarkResponse empty = BenchmarkService.assemble(PIPELINE, null, null, AXIS,
                null, Map.of(), Map.of());
        // Own org: zero measured founders, named as such.
        assertThat(empty.pillars().get(0).org().sufficient()).isFalse();
        assertThat(empty.pillars().get(0).org().sampleSize()).isZero();
        // Platform: absent row (HAVING floor) -> no observed count at all.
        assertThat(empty.pillars().get(0).platform().sufficient()).isFalse();
        assertThat(empty.pillars().get(0).platform().sampleSize()).isNull();
        // No cohort requested -> no cohort segment.
        assertThat(empty.pillars().get(0).cohort()).isNull();
    }

    @Test
    void requestedCohortEchoesIdAndName() {
        UUID cohortId = UUID.randomUUID();
        BenchmarkResponse response = BenchmarkService.assemble(PIPELINE, cohortId, "Spring 26",
                AXIS, Map.of(PILLAR, row(3, null)), Map.of(), Map.of());
        assertThat(response.cohortId()).isEqualTo(cohortId);
        assertThat(response.cohortName()).isEqualTo("Spring 26");
        assertThat(response.pillars().get(0).cohort().sampleSize()).isEqualTo(3);
        assertThat(response.pillars().get(0).cohort().sufficient()).isFalse();
    }
}
