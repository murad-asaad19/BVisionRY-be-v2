package com.bvisionry.comparison.web;

import com.bvisionry.comparison.repository.ComparisonReadRepository.PillarRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure name-match seed (spec §5: auto-match by name, case-insensitive). */
class ComparisonMappingServiceTest {

    private static PillarRow p(String name, int order) {
        return new PillarRow(UUID.randomUUID(), name, order);
    }

    @Test
    void matchesByName_caseInsensitive_andTrimmed() {
        PillarRow vision = p("Vision", 0);
        PillarRow focus = p("  Focus & Flow ", 1);
        PillarRow visionD = p("vision", 0);
        PillarRow focusD = p("Focus & Flow", 1);

        Map<UUID, UUID> matched = ComparisonMappingService.autoMatch(
                List.of(vision, focus), List.of(visionD, focusD));

        assertThat(matched).containsExactlyInAnyOrderEntriesOf(Map.of(
                vision.id(), visionD.id(),
                focus.id(), focusD.id()));
    }

    @Test
    void unmatchedPillars_onEitherSide_stayOut() {
        PillarRow vision = p("Vision", 0);
        PillarRow legacy = p("Legacy", 1);      // dropped in the distance pipeline
        PillarRow visionD = p("Vision", 0);
        PillarRow newP = p("Resilience", 1);    // newly measured

        Map<UUID, UUID> matched = ComparisonMappingService.autoMatch(
                List.of(vision, legacy), List.of(visionD, newP));

        assertThat(matched).containsOnlyKeys(vision.id());
        assertThat(matched.get(vision.id())).isEqualTo(visionD.id());
    }

    @Test
    void duplicateNames_matchDeterministically_firstByListOrder() {
        PillarRow b1 = p("Vision", 0);
        PillarRow b2 = p("Vision", 1);
        PillarRow d1 = p("Vision", 0);

        Map<UUID, UUID> matched = ComparisonMappingService.autoMatch(
                List.of(b1, b2), List.of(d1));

        // One distance pillar can only be claimed once.
        assertThat(matched).containsExactlyEntriesOf(Map.of(b1.id(), d1.id()));
    }
}
