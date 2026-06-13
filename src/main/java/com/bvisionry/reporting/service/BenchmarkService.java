package com.bvisionry.reporting.service;

import com.bvisionry.config.CacheConfig;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.pipeline.repository.PillarRepository;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.reporting.dto.BenchmarkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenchmarkService {

    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final PillarRepository pillarRepository;

    /**
     * Get platform averages and team percentile ranking for benchmarking.
     * Redis-cached with 1-hour TTL.
     */
    @Cacheable(value = CacheConfig.PLATFORM_AVERAGES, key = "#orgId + '-' + #pipelineId")
    public BenchmarkResponse getBenchmarks(UUID orgId, UUID pipelineId, BigDecimal teamOverallAvg) {
        BigDecimal platformOverallAvg = overallSummaryRepository.findPlatformAverageOverall(pipelineId);
        if (platformOverallAvg == null) {
            platformOverallAvg = BigDecimal.ZERO;
        }

        // Team percentile for overall score
        List<BigDecimal> allOverallScores = overallSummaryRepository.findAllOverallScores(pipelineId);
        int teamOverallPercentile = calculatePercentile(allOverallScores, teamOverallAvg);

        // Per-pillar benchmarks
        List<Pillar> pillars = pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId);

        List<BenchmarkResponse.PillarBenchmark> pillarBenchmarks = pillars.stream()
                .map(pillar -> {
                    BigDecimal platformAvg = pillarEvaluationRepository
                            .findPlatformAverageByPillar(pipelineId, pillar.getId());
                    BigDecimal orgAvg = pillarEvaluationRepository
                            .findOrgAverageByPillar(orgId, pipelineId, pillar.getId());
                    List<BigDecimal> allScores = pillarEvaluationRepository
                            .findAllScoresByPillar(pipelineId, pillar.getId());

                    int percentile = calculatePercentile(allScores,
                            orgAvg != null ? orgAvg : BigDecimal.ZERO);

                    return new BenchmarkResponse.PillarBenchmark(
                            pillar.getId(),
                            pillar.getName(),
                            platformAvg != null ? platformAvg : BigDecimal.ZERO,
                            orgAvg != null ? orgAvg : BigDecimal.ZERO,
                            percentile
                    );
                })
                .toList();

        return new BenchmarkResponse(
                pipelineId,
                platformOverallAvg,
                teamOverallAvg,
                teamOverallPercentile,
                pillarBenchmarks
        );
    }

    /**
     * Calculate percentile rank: percentage of scores below the given value.
     */
    private int calculatePercentile(List<BigDecimal> sortedScores, BigDecimal value) {
        if (sortedScores.isEmpty() || value == null) {
            return 0;
        }
        long below = sortedScores.stream()
                .filter(score -> score.compareTo(value) < 0)
                .count();
        return (int) (below * 100 / sortedScores.size());
    }
}
