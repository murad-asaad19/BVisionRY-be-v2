package com.bvisionry.engagement.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bvisionry.common.scoringconfig.ParticipationFormula;
import com.bvisionry.common.scoringconfig.ParticipationFormula.Category;
import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.engagement.domain.SessionType;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CategoryScore;
import com.bvisionry.engagement.dto.EngagementRecordResponse.ParticipationDto;
import com.bvisionry.engagement.web.ParticipationScoring.CategoryInput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The participation formula (spec §4): weighted average, renormalization over
 * categories WITH a denominator, band resolution on the result, and the
 * type→category mapping.
 */
class ParticipationScoringTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final List<ScoringBands.Band> BANDS =
            ParticipationFormula.defaultParticipationBands();

    private static CategoryInput input(String key, int weight, boolean computed,
                                       int done, int total) {
        return new CategoryInput(new Category(key, key, weight, computed), done, total);
    }

    @Test
    void fullDataMatchesThePrototypeStory() {
        // Omar: 9/10 · 3/4 · 4/5 · 2/3 at 50/25/15/10 → 82ish, High.
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 50, true, 9, 10),
                input("workshops", 25, false, 3, 4),
                input("coaching_1on1", 15, false, 4, 5),
                input("coaching_group", 10, false, 2, 3)), BANDS, NOW);

        assertThat(result.score()).isEqualByComparingTo("82.4");
        assertThat(result.bandKey()).isEqualTo("band_1");
        assertThat(result.bandLabel()).isEqualTo("High");
        assertThat(result.computedAt()).isEqualTo(NOW);

        CategoryScore assignments = result.categories().get(0);
        assertThat(assignments.pct()).isEqualByComparingTo("90.0");
        assertThat(assignments.effectiveWeight()).isEqualByComparingTo("50.0");
        assertThat(assignments.points()).isEqualByComparingTo("45.0");
    }

    @Test
    void categoriesWithoutADenominatorAreRenormalizedAway() {
        // No coaching sessions held: their 25 combined weight must NOT count
        // as zero — weights renormalize over assignments (50) + workshops (25).
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 50, true, 8, 10),
                input("workshops", 25, false, 4, 4),
                input("coaching_1on1", 15, false, 0, 0),
                input("coaching_group", 10, false, 0, 0)), BANDS, NOW);

        // (80*50 + 100*25) / 75 = 86.7 — not (40 + 25) = 65.
        assertThat(result.score()).isEqualByComparingTo("86.7");
        assertThat(result.bandKey()).isEqualTo("band_1");

        CategoryScore coaching = result.categories().get(2);
        assertThat(coaching.pct()).isNull();
        assertThat(coaching.effectiveWeight()).isNull();
        assertThat(coaching.points()).isNull();
        // The excluded rows stay visible with their configured weight.
        assertThat(coaching.weight()).isEqualTo(15);

        CategoryScore workshops = result.categories().get(1);
        assertThat(workshops.effectiveWeight()).isEqualByComparingTo("33.3");
    }

    @Test
    void noDenominatorsAnywhereMeansNoScoreNotZero() {
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 50, true, 0, 0),
                input("workshops", 25, false, 0, 0)), BANDS, NOW);

        assertThat(result.score()).isNull();
        assertThat(result.bandKey()).isNull();
        assertThat(result.bandLabel()).isNull();
        assertThat(result.categories()).hasSize(2);
    }

    @Test
    void onlyWeightZeroCategoriesHavingDataMustNotDivideByZero() {
        // Valid config: assignments 100 / workshops 0. Nothing assigned yet,
        // but one workshop was held — the included weight sum is 0, which must
        // yield "no score yet", never an ArithmeticException → 500.
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 100, true, 0, 0),
                input("workshops", 0, false, 1, 1)), BANDS, NOW);

        assertThat(result.score()).isNull();
        assertThat(result.bandKey()).isNull();
        assertThat(result.bandLabel()).isNull();
        // The weight-0 row is excluded from the math, not divided by zero.
        CategoryScore workshops = result.categories().get(1);
        assertThat(workshops.pct()).isNull();
        assertThat(workshops.effectiveWeight()).isNull();
        assertThat(workshops.points()).isNull();
    }

    @Test
    void zeroAttendanceWithHeldSessionsIsARealZero() {
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 50, true, 0, 5),
                input("workshops", 50, false, 0, 2)), BANDS, NOW);

        assertThat(result.score()).isEqualByComparingTo("0.0");
        assertThat(result.bandKey()).isEqualTo("band_3");
        assertThat(result.bandLabel()).isEqualTo("Low");
    }

    @Test
    void bandBoundariesResolveOnTheConfiguredCutoffs() {
        ParticipationDto atEighty = ParticipationScoring.score(
                List.of(input("assignments", 100, true, 4, 5)), BANDS, NOW);
        assertThat(atEighty.score()).isEqualByComparingTo("80.0");
        assertThat(atEighty.bandKey()).isEqualTo("band_1");

        ParticipationDto belowFifty = ParticipationScoring.score(
                List.of(input("assignments", 100, true, 49, 100)), BANDS, NOW);
        assertThat(belowFifty.bandKey()).isEqualTo("band_3");
    }

    @Test
    void sessionTypesMapToTheShippedCategoryKeys() {
        assertThat(SessionType.WORKSHOP.categoryKey()).isEqualTo("workshops");
        assertThat(SessionType.COACHING_1ON1.categoryKey()).isEqualTo("coaching_1on1");
        assertThat(SessionType.COACHING_GROUP.categoryKey()).isEqualTo("coaching_group");
        // Every session type's category exists in the shipped default formula.
        List<String> defaults = ParticipationFormula.defaultCategories().stream()
                .map(Category::key).toList();
        for (SessionType type : SessionType.values()) {
            assertThat(defaults).contains(type.categoryKey());
        }
    }

    @Test
    void aCategoryUnknownToTheSessionTypesJustContributesNothing() {
        // A super admin added a "mentoring" category no session type feeds:
        // it has no denominator, so it renormalizes away instead of crashing.
        ParticipationDto result = ParticipationScoring.score(List.of(
                input("assignments", 60, true, 3, 4),
                input("mentoring", 40, false, 0, 0)), BANDS, NOW);
        assertThat(result.score()).isEqualByComparingTo("75.0");
    }

    @Test
    void scoreIsExactWeightedMathNotRoundedIntermediates() {
        BigDecimal score = ParticipationScoring.score(List.of(
                input("a", 50, true, 1, 3),
                input("b", 50, false, 2, 3)), BANDS, NOW).score();
        // (33.33… + 66.66…) / 2 = 50.0
        assertThat(score).isEqualByComparingTo("50.0");
    }
}
