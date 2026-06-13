package com.bvisionry.survey.dto;

import java.util.List;

public record SurveyResponsePageDto(
        List<SurveyResponseListItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
