package com.bvisionry.reporting.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PillarDetailResponse(
        UUID pillarId,
        String pillarName,
        String iconKey,
        BigDecimal scorePercentage,
        String maturityLabel,
        String whatThisScoreMeans,
        List<String> whatsWorking,
        List<String> whatCanImprove,
        String whyThisMattersForBusiness,
        Integer selfAssessmentGap
) {}
