package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.survey.entity.SurveyQuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurveyQuestionConfigValidatorTest {

    private final SurveyQuestionConfigValidator validator = new SurveyQuestionConfigValidator();

    @Test
    void shortText_acceptsNullConfig() {
        assertThatCode(() -> validator.validate(SurveyQuestionType.SHORT_TEXT, null))
                .doesNotThrowAnyException();
    }

    @Test
    void shortText_acceptsEmptyConfig() {
        assertThatCode(() -> validator.validate(SurveyQuestionType.SHORT_TEXT, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void shortText_rejectsNonNumericMinLength() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.SHORT_TEXT, Map.of("minLength", "50")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shortText_rejectsNegativeMaxLength() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.SHORT_TEXT, Map.of("maxLength", -1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shortText_rejectsMaxLessThanMin() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.SHORT_TEXT, Map.of("minLength", 10, "maxLength", 5)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shortText_acceptsValid() {
        assertThatCode(() -> validator.validate(
                SurveyQuestionType.SHORT_TEXT, Map.of("minLength", 0, "maxLength", 500)))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleChoice_requiresTwoPlusOptions() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.MULTIPLE_CHOICE, Map.of("options", List.of("only"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void multipleChoice_rejectsDuplicates() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.MULTIPLE_CHOICE, Map.of("options", List.of("A", "A"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void multipleChoice_acceptsValid() {
        assertThatCode(() -> validator.validate(
                SurveyQuestionType.MULTIPLE_CHOICE, Map.of("options", List.of("Red", "Blue", "Green"))))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleChoice_acceptsMultiSelectBoolean() {
        assertThatCode(() -> validator.validate(
                SurveyQuestionType.MULTIPLE_CHOICE,
                Map.of("options", List.of("A", "B"), "multiSelect", true)))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleChoice_rejectsNonBooleanMultiSelect() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.MULTIPLE_CHOICE,
                Map.of("options", List.of("A", "B"), "multiSelect", "yes")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void likert_requiresLabels() {
        assertThatThrownBy(() -> validator.validate(SurveyQuestionType.LIKERT, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.LIKERT, Map.of("labels", List.of("only-one"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void likert_requiresExactlyFiveLabels() {
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.LIKERT,
                Map.of("labels", List.of("a", "b", "c", "d"))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(
                SurveyQuestionType.LIKERT,
                Map.of("labels", List.of("a", "b", "c", "d", "e", "f"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void likert_acceptsFiveLabels() {
        assertThatCode(() -> validator.validate(
                SurveyQuestionType.LIKERT,
                Map.of("labels", List.of(
                        "Strongly Disagree", "Disagree", "Neutral", "Agree", "Strongly Agree"))))
                .doesNotThrowAnyException();
    }

    @Test
    void number_acceptsEmptyConfig() {
        assertThatCode(() -> validator.validate(SurveyQuestionType.NUMBER, Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(SurveyQuestionType.NUMBER, null))
                .doesNotThrowAnyException();
    }
}
