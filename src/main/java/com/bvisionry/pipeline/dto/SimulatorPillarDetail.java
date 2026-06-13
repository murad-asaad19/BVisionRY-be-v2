package com.bvisionry.pipeline.dto;

import com.bvisionry.common.dto.PillarEvaluationResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only simulator projection. Carries audit/debug fields (evidence, rubric snapshot,
 * provenance, raw model output) that must not be exposed on member-facing endpoints.
 */
public record SimulatorPillarDetail(
        UUID pillarId,
        String pillarName,
        String iconKey,
        BigDecimal scorePercentage,
        String maturityLabel,
        String whatThisScoreMeans,
        List<String> whatsWorking,
        List<String> whatCanImprove,
        String whyThisMattersForBusiness,
        Integer selfAssessmentGap,
        List<PillarEvaluationResult.Evidence> evidence,
        boolean failed,
        String errorMessage,
        SimulatorProvenance provenance,
        String rubricSnapshot,
        String rawResponse
) {}
