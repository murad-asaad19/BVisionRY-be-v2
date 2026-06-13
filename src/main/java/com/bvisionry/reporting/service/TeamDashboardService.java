package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.config.CacheConfig;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.reporting.dto.CompletionStatsResponse;
import com.bvisionry.reporting.dto.DashboardOverviewResponse;
import com.bvisionry.reporting.dto.HistogramBucket;
import com.bvisionry.reporting.dto.MemberScoreRow;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import com.bvisionry.reporting.dto.ScoreDistributionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamDashboardService {

    private final SubmissionRepository submissionRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;

    /**
     * Dashboard overview: member scores grid filterable/sortable by pillar, score range, completion.
     */
    @Cacheable(value = CacheConfig.DASHBOARD_OVERVIEW, key = "#orgId + '-' + #pipelineId")
    public DashboardOverviewResponse getOverview(UUID orgId, UUID pipelineId) {
        List<Submission> submissions = submissionRepository.findByOrgAndPipelineForDashboard(orgId, pipelineId);
        List<PillarEvaluation> allEvaluations = pillarEvaluationRepository.findByOrgAndPipeline(orgId, pipelineId);

        // Group evaluations by submission
        Map<UUID, List<PillarEvaluation>> evalsBySubmission = allEvaluations.stream()
                .collect(Collectors.groupingBy(e -> e.getSubmission().getId()));

        String pipelineName = submissions.isEmpty() ? "" :
                submissions.getFirst().getAssignment().getPipeline().getName();

        List<UUID> submissionIds = submissions.stream().map(Submission::getId).toList();
        Map<UUID, OverallSummary> summaryBySubmission = overallSummaryRepository
                .findBySubmissionIdIn(submissionIds).stream()
                .collect(Collectors.toMap(s -> s.getSubmission().getId(), s -> s));

        List<MemberScoreRow> memberRows = submissions.stream()
                .map(submission -> {
                    List<PillarEvaluation> submissionEvals =
                            evalsBySubmission.getOrDefault(submission.getId(), List.of());

                    List<PillarScoreSummary> pillarScores = submissionEvals.stream()
                            .map(eval -> new PillarScoreSummary(
                                    eval.getPillar().getId(),
                                    eval.getPillar().getName(),
                                    eval.getPillar().getIconKey(),
                                    eval.getScorePercentage(),
                                    eval.getMaturityLabel()
                            ))
                            .toList();

                    BigDecimal overallScore = summaryBySubmission.containsKey(submission.getId())
                            ? summaryBySubmission.get(submission.getId()).getOverallScorePercentage()
                            : null;

                    return new MemberScoreRow(
                            submission.getUser().getId(),
                            submission.getId(),
                            submission.getUser().getName(),
                            submission.getUser().getEmail(),
                            overallScore,
                            submission.getStatus().name(),
                            submission.getEvaluatedAt(),
                            pillarScores,
                            submission.getAssignment().getId(),
                            submission.getAssignment().getDeadline(),
                            submission.getDeadlineOverride()
                    );
                })
                .toList();

        int evaluatedCount = (int) submissions.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.EVALUATED)
                .count();

        return new DashboardOverviewResponse(
                pipelineId,
                pipelineName,
                submissions.size(),
                evaluatedCount,
                memberRows
        );
    }

    /**
     * Score distribution: histograms per pillar with 5 buckets (0-20, 21-40, 41-60, 61-80, 81-100).
     */
    @Cacheable(value = CacheConfig.DASHBOARD_DISTRIBUTION, key = "#orgId + '-' + #pipelineId")
    public ScoreDistributionResponse getDistribution(UUID orgId, UUID pipelineId) {
        List<PillarEvaluation> evaluations = pillarEvaluationRepository.findByOrgAndPipeline(orgId, pipelineId);

        Map<UUID, List<PillarEvaluation>> byPillar = evaluations.stream()
                .collect(Collectors.groupingBy(e -> e.getPillar().getId()));

        List<ScoreDistributionResponse.PillarDistribution> distributions = byPillar.entrySet().stream()
                .map(entry -> {
                    List<PillarEvaluation> pillarEvals = entry.getValue();
                    String pillarName = pillarEvals.getFirst().getPillar().getName();

                    List<HistogramBucket> buckets = buildHistogramBuckets(pillarEvals);

                    return new ScoreDistributionResponse.PillarDistribution(
                            entry.getKey(), pillarName, buckets);
                })
                .toList();

        return new ScoreDistributionResponse(distributions);
    }

    /**
     * Completion stats: total assigned, in progress, submitted, evaluated, completion rate.
     */
    @Cacheable(value = CacheConfig.DASHBOARD_COMPLETION, key = "#orgId + '-' + #pipelineId")
    public CompletionStatsResponse getCompletion(UUID orgId, UUID pipelineId) {
        List<Object[]> statusCounts = submissionRepository.countByStatusForOrgPipeline(orgId, pipelineId);

        int inProgress = 0, submitted = 0, evaluated = 0;

        for (Object[] row : statusCounts) {
            SubmissionStatus status = (SubmissionStatus) row[0];
            long count = (Long) row[1];
            switch (status) {
                case IN_PROGRESS -> inProgress = (int) count;
                case SUBMITTED -> submitted = (int) count;
                // PENDING_REEDIT keeps the existing evaluation in place — the
                // admin has only re-opened editing for some pillars — so it
                // continues to count as evaluated for completion reporting.
                case EVALUATED, PENDING_REEDIT -> evaluated = (int) count;
                case FAILED -> { /* not surfaced in completion stats */ }
            }
        }

        int total = inProgress + submitted + evaluated;
        BigDecimal completionRate = total == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(submitted + evaluated)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        return new CompletionStatsResponse(total, inProgress, submitted, evaluated, completionRate);
    }

    private List<HistogramBucket> buildHistogramBuckets(List<PillarEvaluation> evaluations) {
        int[] bucketCounts = new int[5]; // 0-20, 21-40, 41-60, 61-80, 81-100
        int[][] ranges = {{0, 20}, {21, 40}, {41, 60}, {61, 80}, {81, 100}};
        String[] labels = {"0-20", "21-40", "41-60", "61-80", "81-100"};

        for (PillarEvaluation eval : evaluations) {
            int score = eval.getScorePercentage().intValue();
            if (score <= 20) bucketCounts[0]++;
            else if (score <= 40) bucketCounts[1]++;
            else if (score <= 60) bucketCounts[2]++;
            else if (score <= 80) bucketCounts[3]++;
            else bucketCounts[4]++;
        }

        List<HistogramBucket> buckets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buckets.add(new HistogramBucket(ranges[i][0], ranges[i][1], labels[i], bucketCounts[i]));
        }
        return buckets;
    }
}
