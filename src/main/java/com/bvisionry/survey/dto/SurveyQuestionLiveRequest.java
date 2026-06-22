package com.bvisionry.survey.dto;

/**
 * Body for toggling a question's inclusion on the results "Live" analytics page.
 * {@code Boolean} (not primitive) so a missing value deserializes to null
 * rather than 400-ing; the service treats null as false.
 */
public record SurveyQuestionLiveRequest(Boolean enabled) {}
