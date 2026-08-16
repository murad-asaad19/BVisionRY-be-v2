package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.CohortGrowthSummaryResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for the cohort-level growth report
 * (spec §4). Same shape as {@link TeamInsightGenerator}: the typed return drives
 * structured output, {@link Result} carries token usage + finish reason for the
 * call log.
 *
 * <p>The argument is the user message: per-member comparison rows, the approved
 * narratives across the cohort, and the pillar aggregate. Members are labelled
 * "Member N" unless the generation explicitly asked for real names (§4).
 */
public interface CohortGrowthSummaryWriter {
    Result<CohortGrowthSummaryResult> write(String cohortData);
}
