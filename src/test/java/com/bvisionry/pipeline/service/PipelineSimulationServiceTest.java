package com.bvisionry.pipeline.service;

import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.evaluation.EvaluationEngine;
import com.bvisionry.evaluation.EvaluationEngine.PipelineEvaluationResult;
import com.bvisionry.evaluation.EvaluationEngine.SummaryResult;
import com.bvisionry.pipeline.dto.SimulateRequest;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineSimulationServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private EvaluationEngine evaluationEngine;
    @Mock private AIConfigService aiConfigService;

    private PipelineSimulationService service;

    private final UUID pipelineId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PipelineSimulationService(pipelineRepository, evaluationEngine, aiConfigService);

        Pipeline pipeline = mock(Pipeline.class);
        lenient().when(pipeline.getPillars()).thenReturn(List.of());
        lenient().when(pipeline.getName()).thenReturn("Test Pipeline");
        lenient().when(pipeline.getOverallSummaryPrompt()).thenReturn("overall-summary");
        lenient().when(pipeline.getFreeTierPrompt()).thenReturn("free-summary");
        when(pipelineRepository.findByIdWithPillarsAndQuestions(pipelineId))
                .thenReturn(Optional.of(pipeline));

        SummaryResult summary = new SummaryResult(
                BigDecimal.valueOf(70), "narrative",
                List.of(), List.of(),
                null, null, null, null, null, false);
        when(evaluationEngine.evaluatePipeline(any(), isNull(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(new PipelineEvaluationResult(List.of(), summary));
    }

    private SimulateRequest request(SubscriptionTier tier, boolean publicAssessment) {
        return new SimulateRequest(Map.of(), tier, publicAssessment);
    }

    @Test
    void simulate_publicAssessment_runsAsPremiumWithPublicPromptAndModel() {
        AIConfiguration cfg = mock(AIConfiguration.class);
        when(cfg.getPublicAssessmentModel()).thenReturn("public-haiku");
        when(aiConfigService.getConfigEntity()).thenReturn(cfg);

        service.simulate(pipelineId, request(SubscriptionTier.PREMIUM, true));

        // Public mirrors the real QR-link flow: premium gating (freeTier=false),
        // overall-summary prompt, the public model override, and publicAssessment=true.
        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("overall-summary"), eq(false), eq("public-haiku"), eq(true));
    }

    @Test
    void simulate_premium_noPublicFlagNoModelOverride() {
        service.simulate(pipelineId, request(SubscriptionTier.PREMIUM, false));

        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("overall-summary"), eq(false), isNull(), eq(false));
    }

    @Test
    void simulate_free_usesFreeTierPromptNoPublic() {
        service.simulate(pipelineId, request(SubscriptionTier.FREE, false));

        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("free-summary"), eq(true), isNull(), eq(false));
    }
}
