package com.bvisionry.aiengine.eval;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.mock.MockLangChainChatModel;
import com.bvisionry.aiengine.resilience.AiResilience;
import com.bvisionry.aiengine.service.AiEvaluationEngine;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the harness correctly measures a model against the golden set. Backed
 * by the deterministic mock (always scores 72), so a case banded around 72 passes
 * and one banded above it fails — proving the harness distinguishes in-band from
 * out-of-band and reports schema-validity and latency.
 */
class ModelEvalHarnessTest {

    private ModelEvalHarness harness() {
        MockLangChainChatModel model = new MockLangChainChatModel();
        Lc4jChatModelProvider provider = new Lc4jChatModelProvider(
                mock(AIConfigService.class), mock(ModelCapabilityRegistry.class)) {
            @Override
            public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                return model;
            }
        };
        AiEvaluationEngine engine = new AiEvaluationEngine(
                provider, AiResilience.withDefaults(new SimpleMeterRegistry()), 2);
        return new ModelEvalHarness(engine);
    }

    @Test
    void measuresInBandVersusOutOfBand() {
        ModelEvalReport report = harness().run("anthropic/claude-sonnet-4", 0.3, 1024, List.of(
                new GoldenCase("in-band", "rubric", "<assessment_data>x</assessment_data>", 60, 79),
                new GoldenCase("out-of-band", "rubric", "<assessment_data>x</assessment_data>", 80, 100)));

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.schemaValidCount()).isEqualTo(2);   // both parsed cleanly
        assertThat(report.inBandCount()).isEqualTo(1);        // only the 60-79 band contains 72
        assertThat(report.passRate()).isEqualTo(0.5);

        ModelEvalReport.CaseResult inBand = report.cases().get(0);
        assertThat(inBand.score()).isEqualTo(72);
        assertThat(inBand.inBand()).isTrue();
        assertThat(report.cases().get(1).inBand()).isFalse();
        assertThat(report.summary()).contains("in-band");
    }

    @Test
    void defaultGoldenSet_isUsableAndAllSchemaValid() {
        ModelEvalReport report = harness().run("anthropic/claude-sonnet-4", 0.3, 1024,
                ModelEvalHarness.defaultGoldenSet());

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.schemaValidCount()).isEqualTo(2);
    }
}
