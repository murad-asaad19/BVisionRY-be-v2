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
        List<SnippetDto> recentSnippets,
        /** Per-country tallies by ISO-3166 alpha-2 code — populated for COUNTRY questions, else null. Drives the live map. */
        List<GeoPointDto> geoPoints
) {
    public record HistogramBucketDto(String label, long count) {}

    public record SnippetDto(String text, Instant submittedAt) {}

    public record GeoPointDto(String code, long count) {}
}
