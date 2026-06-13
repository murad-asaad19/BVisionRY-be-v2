package com.bvisionry.reporting.service;

import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.pipeline.repository.PillarRepository;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.reporting.dto.BenchmarkResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkServiceTest {

    @Mock
    private PillarEvaluationRepository pillarEvaluationRepository;

    @Mock
    private OverallSummaryRepository overallSummaryRepository;

    @Mock
    private PillarRepository pillarRepository;

    @InjectMocks
    private BenchmarkService benchmarkService;

    @Test
    void getBenchmarks_returnsPercentileAndAverages() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID pillarId = UUID.randomUUID();

        Pillar pillar = new Pillar();
        pillar.setId(pillarId);
        pillar.setName("Communication");

        when(pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId))
                .thenReturn(List.of(pillar));

        // Platform average for pillar
        when(pillarEvaluationRepository.findPlatformAverageByPillar(pipelineId, pillarId))
                .thenReturn(new BigDecimal("65.00"));

        // Org average for pillar
        when(pillarEvaluationRepository.findOrgAverageByPillar(orgId, pipelineId, pillarId))
                .thenReturn(new BigDecimal("72.00"));

        // All scores for percentile calc (10 scores, team at 72 is above 7 of them)
        when(pillarEvaluationRepository.findAllScoresByPillar(pipelineId, pillarId))
                .thenReturn(List.of(
                        new BigDecimal("40"), new BigDecimal("45"), new BigDecimal("50"),
                        new BigDecimal("55"), new BigDecimal("60"), new BigDecimal("65"),
                        new BigDecimal("70"), new BigDecimal("72"), new BigDecimal("80"),
                        new BigDecimal("90")
                ));

        // Overall platform average
        when(overallSummaryRepository.findPlatformAverageOverall(pipelineId))
                .thenReturn(new BigDecimal("62.00"));

        // All overall scores for org percentile
        when(overallSummaryRepository.findAllOverallScores(pipelineId))
                .thenReturn(List.of(
                        new BigDecimal("50"), new BigDecimal("55"), new BigDecimal("60"),
                        new BigDecimal("65"), new BigDecimal("70"), new BigDecimal("75")
                ));

        BenchmarkResponse response = benchmarkService.getBenchmarks(orgId, pipelineId,
                new BigDecimal("72.00"));

        assertThat(response.platformOverallAverage()).isEqualByComparingTo(new BigDecimal("62.00"));
        assertThat(response.teamOverallAverage()).isEqualByComparingTo(new BigDecimal("72.00"));
        assertThat(response.pillarBenchmarks()).hasSize(1);
        assertThat(response.pillarBenchmarks().getFirst().platformAverage())
                .isEqualByComparingTo(new BigDecimal("65.00"));
        assertThat(response.pillarBenchmarks().getFirst().teamAverage())
                .isEqualByComparingTo(new BigDecimal("72.00"));
        // Percentile: 72 is above 7/10 scores = 70th percentile
        assertThat(response.pillarBenchmarks().getFirst().teamPercentile()).isEqualTo(70);
    }

    @Test
    void getBenchmarks_noData_returnsZeros() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        when(pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId))
                .thenReturn(List.of());
        when(overallSummaryRepository.findPlatformAverageOverall(pipelineId))
                .thenReturn(null);
        when(overallSummaryRepository.findAllOverallScores(pipelineId))
                .thenReturn(List.of());

        BenchmarkResponse response = benchmarkService.getBenchmarks(orgId, pipelineId,
                BigDecimal.ZERO);

        assertThat(response.pillarBenchmarks()).isEmpty();
        assertThat(response.platformOverallAverage()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
