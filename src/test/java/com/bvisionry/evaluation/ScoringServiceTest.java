package com.bvisionry.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceTest {

    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService();
    }

    @Nested
    class MaturityLabelDerivation {

        @Test
        void deriveMaturityLabel_emerging() {
            Map<String, List<Integer>> thresholds = Map.of(
                    "Emerging", List.of(0, 59),
                    "Strong", List.of(60, 79),
                    "Elite", List.of(80, 100)
            );
            String label = scoringService.deriveMaturityLabel(new BigDecimal("45"), thresholds);
            assertThat(label).isEqualTo("Emerging");
        }

        @Test
        void deriveMaturityLabel_strong() {
            Map<String, List<Integer>> thresholds = Map.of(
                    "Emerging", List.of(0, 59),
                    "Strong", List.of(60, 79),
                    "Elite", List.of(80, 100)
            );
            String label = scoringService.deriveMaturityLabel(new BigDecimal("72"), thresholds);
            assertThat(label).isEqualTo("Strong");
        }

        @Test
        void deriveMaturityLabel_elite() {
            Map<String, List<Integer>> thresholds = Map.of(
                    "Emerging", List.of(0, 59),
                    "Strong", List.of(60, 79),
                    "Elite", List.of(80, 100)
            );
            String label = scoringService.deriveMaturityLabel(new BigDecimal("95"), thresholds);
            assertThat(label).isEqualTo("Elite");
        }

        @Test
        void deriveMaturityLabel_boundary_60_isStrong() {
            Map<String, List<Integer>> thresholds = Map.of(
                    "Emerging", List.of(0, 59),
                    "Strong", List.of(60, 79),
                    "Elite", List.of(80, 100)
            );
            String label = scoringService.deriveMaturityLabel(new BigDecimal("60"), thresholds);
            assertThat(label).isEqualTo("Strong");
        }

        @Test
        void deriveMaturityLabel_boundary_80_isElite() {
            Map<String, List<Integer>> thresholds = Map.of(
                    "Emerging", List.of(0, 59),
                    "Strong", List.of(60, 79),
                    "Elite", List.of(80, 100)
            );
            String label = scoringService.deriveMaturityLabel(new BigDecimal("80"), thresholds);
            assertThat(label).isEqualTo("Elite");
        }
    }
}
