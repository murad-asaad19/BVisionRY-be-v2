package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyQuestionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QuestionSummaryDto(
        UUID questionId,
        SurveyQuestionType type,
        String promptText,
        long responseCount,
        Map<String, Long> counts,
        BigDecimal average,
        BigDecimal min,
        BigDecimal max,
        List<HistogramBucketDto> histogramBuckets,
        List<SnippetDto> recentSnippets
) {
    public record HistogramBucketDto(String label, long count) {}

    public record SnippetDto(String text, Instant submittedAt) {}
}
