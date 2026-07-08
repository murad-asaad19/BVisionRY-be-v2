package com.bvisionry.insights.service;

import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock private InsightReportRepository insightReportRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private PillarEvaluationRepository pillarEvaluationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private OpenRouterChatService openRouterChatService;

    @InjectMocks private InsightService insightService;

    private Submission evaluatedSubmission() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Submission s = new Submission();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setStatus(SubmissionStatus.EVALUATED);
        return s;
    }

    /**
     * The core guard: EVALUATED submissions exist but have zero pillar evaluations.
     * Without this the aggregate would be a contentless "Team size: N" header and the
     * LLM would fabricate a plausible-but-ungrounded report — never reach the AI call.
     */
    @Test
    void generateInsight_evaluatedButNoPillarEvaluations_failsLoud() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(new Organization()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(new Pipeline()));
        when(submissionRepository.findByOrgAndPipeline(orgId, pipelineId))
                .thenReturn(List.of(evaluatedSubmission()));
        when(pillarEvaluationRepository.findByOrgAndPipeline(orgId, pipelineId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> insightService.generateInsight(orgId, pipelineId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no pillar evaluations");
    }

    /** Pre-existing guard still fires (my new guard must not shadow it). */
    @Test
    void generateInsight_noEvaluatedSubmissions_failsLoud() {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(new Organization()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(new Pipeline()));
        when(submissionRepository.findByOrgAndPipeline(orgId, pipelineId)).thenReturn(List.of());

        assertThatThrownBy(() -> insightService.generateInsight(orgId, pipelineId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No evaluated submissions");
    }
}
