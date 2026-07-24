package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.PillarScoreView;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.reporting.dto.CompletionStatsResponse;
import com.bvisionry.reporting.dto.DashboardOverviewResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import com.bvisionry.reporting.dto.ScoreDistributionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamDashboardServiceTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private PillarEvaluationRepository pillarEvaluationRepository;

    @Mock
    private OverallSummaryRepository overallSummaryRepository;

    @InjectMocks
    private TeamDashboardService teamDashboardService;

    @Test
    void getOverview_returnsMemberScoresGrid() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID pillarId = UUID.randomUUID();

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("John Doe");
        user.setEmail("john@test.com");

        Pipeline pipeline = new Pipeline();
        pipeline.setId(pipelineId);
        pipeline.setName("Leadership Assessment");

        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);

        Submission submission = new Submission();
        submission.setId(UUID.randomUUID());
        submission.setUser(user);
        submission.setAssignment(assignment);
        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());

        PillarScoreView eval = new PillarScoreView(
                submission.getId(), pillarId, "Communication", "chat",
                new BigDecimal("78.00"), "Strong");

        OverallSummary summary = new OverallSummary();
        summary.setSubmission(submission);
        summary.setOverallScorePercentage(new BigDecimal("78.00"));

        when(submissionRepository.findByOrgAndPipelineForDashboard(orgId, pipelineId))
                .thenReturn(List.of(submission));
        when(pillarEvaluationRepository.findScoreViewsByOrgAndPipeline(orgId, pipelineId))
                .thenReturn(List.of(eval));
        when(overallSummaryRepository.findBySubmissionIdIn(List.of(submission.getId())))
                .thenReturn(List.of(summary));

        DashboardOverviewResponse response = teamDashboardService.getOverview(orgId, pipelineId);

        assertThat(response.pipelineName()).isEqualTo("Leadership Assessment");
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().getFirst().memberName()).isEqualTo("John Doe");
        assertThat(response.members().getFirst().overallScore())
                .isEqualByComparingTo(new BigDecimal("78.00"));

        PillarScoreSummary pillarScore = response.members().getFirst().pillarScores().getFirst();
        assertThat(pillarScore.pillarId()).isEqualTo(pillarId);
        assertThat(pillarScore.pillarName()).isEqualTo("Communication");
        assertThat(pillarScore.iconKey()).isEqualTo("chat");
        assertThat(pillarScore.scorePercentage()).isEqualByComparingTo(new BigDecimal("78.00"));
        assertThat(pillarScore.maturityLabel()).isEqualTo("Strong");

        // The aggregation must stay on the score projection — hydrating full
        // PillarEvaluation entities drags the heavy AI payload columns along.
        verify(pillarEvaluationRepository, never()).findByOrgAndPipeline(any(), any());
    }

    @Test
    void getDistribution_returnsHistogramBuckets() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID pillarId = UUID.randomUUID();

        List<PillarScoreView> evals = List.of(
                scoreView(pillarId, "Strategy", "45.00", "Emerging"),
                scoreView(pillarId, "Strategy", "72.00", "Strong"),
                scoreView(pillarId, "Strategy", "88.00", "Elite"));

        when(pillarEvaluationRepository.findScoreViewsByOrgAndPipeline(orgId, pipelineId))
                .thenReturn(evals);

        ScoreDistributionResponse response = teamDashboardService.getDistribution(orgId, pipelineId);

        assertThat(response.pillars()).hasSize(1);
        assertThat(response.pillars().getFirst().pillarName()).isEqualTo("Strategy");
        // Should have 5 buckets: 0-20, 21-40, 41-60, 61-80, 81-100
        assertThat(response.pillars().getFirst().buckets()).hasSize(5);
        assertThat(response.pillars().getFirst().buckets())
                .extracting(b -> b.count())
                .containsExactly(0, 0, 1, 1, 1);

        verify(pillarEvaluationRepository, never()).findByOrgAndPipeline(any(), any());
    }

    @Test
    void getCompletion_returnsCorrectStats() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        List<Object[]> statusCounts = List.of(
                new Object[]{SubmissionStatus.IN_PROGRESS, 3L},
                new Object[]{SubmissionStatus.SUBMITTED, 2L},
                new Object[]{SubmissionStatus.EVALUATED, 5L}
        );

        when(submissionRepository.countByStatusForOrgPipeline(orgId, pipelineId))
                .thenReturn(statusCounts);

        CompletionStatsResponse response = teamDashboardService.getCompletion(orgId, pipelineId);

        assertThat(response.totalAssigned()).isEqualTo(10);
        assertThat(response.inProgress()).isEqualTo(3);
        assertThat(response.submitted()).isEqualTo(2);
        assertThat(response.evaluated()).isEqualTo(5);
        assertThat(response.completionRate()).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    private static PillarScoreView scoreView(UUID pillarId, String name, String score, String label) {
        return new PillarScoreView(UUID.randomUUID(), pillarId, name, name.toLowerCase(),
                new BigDecimal(score), label);
    }
}
