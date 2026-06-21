package com.bvisionry.survey.entity;

public enum SurveyQuestionType {
    SHORT_TEXT,
    MULTIPLE_CHOICE,
    LIKERT,
    NUMBER,
    SELF_RATING,
    /**
     * A single-country selection. The answer stores the respondent's chosen
     * ISO-3166 alpha-2 code (uppercase, e.g. "US") in {@code selectedValue}.
     * Powers the survey-results map.
     */
    COUNTRY
}
