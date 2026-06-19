package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.OverallSummaryResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for the cross-pillar overall summary.
 * See {@link PillarEvaluator} for the construction and return-type rationale.
 */
public interface SummaryGenerator {
    Result<OverallSummaryResult> generate(String pillarResults);
}
