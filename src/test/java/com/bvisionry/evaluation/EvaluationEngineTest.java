package com.bvisionry.evaluation;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiengine.confidence.ConfidenceGate;
import com.bvisionry.common.exception.AIServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationEngineTest {

    @Mock private ScoringService scoringService;
    @Mock private OpenRouterChatService openRouterChatService;
    @Mock private AIConfigService aiConfigService;
    @Mock private ConfidenceGate confidenceGate;

    private EvaluationEngine engine;

    @BeforeEach
    void setUp() {
        // Executors run inline so the engine's fan-out is deterministic in the test.
        Executor inline = Runnable::run;
        engine = new EvaluationEngine(scoringService, openRouterChatService, aiConfigService,
                confidenceGate, inline, inline, true, 3, 2, "");
    }

    @Test
    void callOverallSummary_transportFailure_degradesToFailedSummaryResult() {
        // A transport error on the overall-summary call (429/5xx/circuit-open/bulkhead-full)
        // must degrade to a failed SummaryResult — which lands the submission in NEEDS_REVIEW
        // with the pillar results persisted — rather than propagating out and discarding the
        // successful pillar calls that precede it.
        when(openRouterChatService.generateOverallSummary(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new AIServiceException("429 Too Many Requests"));

        EvaluationEngine.SummaryResult result = engine.generateOverallSummary(
                List.of(), List.of(), "summary prompt", null);

        assertThat(result.failed()).isTrue();
        assertThat(result.overallScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.summaryNarrative()).isEmpty();
        assertThat(result.strengths()).isEmpty();
        assertThat(result.developmentAreas()).isEmpty();
        assertThat(result.corePattern()).isNull();
        assertThat(result.movingForwardNarrative()).isNull();
        // No response was received, so raw response and provenance are null (unlike a parse
        // failure, which still retains the raw body for diagnostics).
        assertThat(result.rawResponse()).isNull();
        assertThat(result.provenance()).isNull();
        assertThat(result.summaryPromptSnapshot()).isEqualTo("summary prompt");
    }
}
