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

import static org.assertj.core.api.Assertions.assertThat;
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
        when(pipelineRepository.findByIdWithPillarsAndQuestions(pipelineId))
                .thenReturn(Optional.of(pipeline));

        // Populate the premium-only blocks (strengths / developmentAreas / corePattern)
        // so the FREE-vs-PREMIUM display scoping in PipelineSimulationService is actually
        // exercised: a FREE simulation must scope these OUT, a PREMIUM one must keep them.
        // With empty/null values the scoped branch would be a silent no-op and a leak of
        // premium detail into a FREE result would pass unnoticed.
        SummaryResult summary = new SummaryResult(
                BigDecimal.valueOf(70), "narrative",
                List.of("strength-1", "strength-2"), List.of("dev-1", "dev-2"),
                "core-pattern", "moving-forward", null, null, null, false);
        when(evaluationEngine.evaluatePipeline(any(), isNull(), any(), any(), any(), anyBoolean()))
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

        // Public mirrors the real QR-link flow: the overall-summary prompt, the public
        // model override, and publicAssessment=true. Generation is tier-agnostic now.
        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("overall-summary"), eq("public-haiku"), eq(true));
    }

    @Test
    void simulate_premium_noPublicFlagNoModelOverride() {
        var result = service.simulate(pipelineId, request(SubscriptionTier.PREMIUM, false));

        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("overall-summary"), isNull(), eq(false));

        // PREMIUM display scope INCLUDES the premium-only blocks the engine produced.
        var results = result.results();
        assertThat(results.premiumFeaturesAvailable()).isTrue();
        assertThat(results.strengths()).containsExactly("strength-1", "strength-2");
        assertThat(results.developmentAreas()).containsExactly("dev-1", "dev-2");
        assertThat(results.corePattern()).isEqualTo("core-pattern");
    }

    @Test
    void simulate_free_generatesPremiumSummaryButScopesOutPremiumDetail() {
        var result = service.simulate(pipelineId, request(SubscriptionTier.FREE, false));

        // Generation no longer branches on tier: even a FREE simulation runs the full
        // premium summary prompt. Free vs premium is a display scope, not a generation
        // switch — so the engine sees the overall-summary prompt, same as premium.
        verify(evaluationEngine).evaluatePipeline(
                any(), isNull(), any(),
                eq("overall-summary"), isNull(), eq(false));

        // ...but the FREE display scope must SUPPRESS the premium-only blocks even though
        // the engine generated them — a regression that leaks premium detail fails here.
        var results = result.results();
        assertThat(results.premiumFeaturesAvailable()).isFalse();
        assertThat(results.strengths()).isEmpty();
        assertThat(results.developmentAreas()).isEmpty();
        assertThat(results.corePattern()).isNull();
    }
}
