package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.TeamInsightResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for team-level insight generation.
 * See {@link PillarEvaluator} for the construction and return-type rationale.
 */
public interface TeamInsightGenerator {
    Result<TeamInsightResult> generate(String aggregatedData);
}
