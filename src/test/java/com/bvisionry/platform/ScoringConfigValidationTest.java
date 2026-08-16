package com.bvisionry.platform;

import com.bvisionry.common.exception.FieldValidationException;
import com.bvisionry.platform.dto.ScoringConfigResponse.ParticipationCategory;
import com.bvisionry.platform.dto.ScoringConfigResponse.QualityTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** Structural rules of the Scoring &amp; Labels config (spec §7). */
class ScoringConfigValidationTest {

    private static ParticipationCategory cat(String key, int weight, boolean computed) {
        return new ParticipationCategory(key, key, weight, computed);
    }

    @Test
    void shippedDefaultFormula_isValid() {
        assertThatCode(() -> ScoringConfigService.validateFormula(
                ScoringConfigService.defaultCategories()))
                .doesNotThrowAnyException();
    }

    @Test
    void weightsNotSumming100_failOnTheCategoriesField() {
        assertThatThrownBy(() -> ScoringConfigService.validateFormula(List.of(
                cat("assignments", 50, true), cat("workshops", 40, false))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getFieldErrors())
                                .containsKey("categories")
                                .hasEntrySatisfying("categories",
                                        msg -> assertThat(msg).contains("90")));
    }

    @Test
    void removingTheProtectedAssignmentsCategory_fails() {
        assertThatThrownBy(() -> ScoringConfigService.validateFormula(List.of(
                cat("workshops", 60, false), cat("coaching_1on1", 40, false))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getFieldErrors().get("categories"))
                                .contains("protected"));
    }

    @Test
    void assignmentsMarkedManual_fails() {
        assertThatThrownBy(() -> ScoringConfigService.validateFormula(List.of(
                cat("assignments", 100, false))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getFieldErrors())
                                .containsKey("categories[0].computed"));
    }

    @Test
    void duplicateCategoryKeys_failPerRow() {
        assertThatThrownBy(() -> ScoringConfigService.validateFormula(List.of(
                cat("assignments", 50, true), cat("assignments", 50, true))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getFieldErrors())
                                .containsKey("categories[1].key"));
    }

    @Test
    void qualityTags_defaultsValid_emptyAndDuplicatesRejected() {
        assertThatCode(() -> ScoringConfigService.validateQualityTags(
                ScoringConfigService.defaultQualityTags()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScoringConfigService.validateQualityTags(List.of()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> ScoringConfigService.validateQualityTags(List.of(
                new QualityTag("thin", "Thin"), new QualityTag("thin", "Thin again"))))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("tags[1].key"));
    }
}
