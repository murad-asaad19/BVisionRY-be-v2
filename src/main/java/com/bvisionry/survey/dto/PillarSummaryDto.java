package com.bvisionry.survey.dto;

import java.util.List;
import java.util.UUID;

public record PillarSummaryDto(
        UUID pillarId,
        String name,
        String description,
        int displayOrder,
        List<QuestionSummaryDto> questionSummaries
) {}
