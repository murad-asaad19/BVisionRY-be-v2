package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.AiUseDetectionResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for the AI-use detection call: judges how
 * likely a submission's free-text answers were AI-generated. Same construction
 * contract as {@link PillarEvaluator} — built per call so the DB-driven system
 * prompt is supplied via {@code systemMessageProvider}; the argument is the
 * user message (the assessment XML).
 *
 * <p>{@link Result} is returned (not the bare DTO) so the caller can read token
 * usage and finish reason for the audit log without a provider-specific cast.
 */
public interface AiUseDetector {
    Result<AiUseDetectionResult> detect(String assessment);
}
