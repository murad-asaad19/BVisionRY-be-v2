package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.PillarEvaluationResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for a single-pillar evaluation. The typed
 * return drives structured output (native JSON schema when the configured model
 * supports it, prompt-instructed JSON + guardrail repair otherwise). Built
 * per-call so the DB-driven system prompt is supplied via
 * {@code systemMessageProvider}; the argument is the user message.
 *
 * <p>{@link Result} is returned (not the bare DTO) so the caller can read token
 * usage and finish reason for the audit log without a provider-specific cast.
 */
public interface PillarEvaluator {
    Result<PillarEvaluationResult> evaluate(String assessment);
}
