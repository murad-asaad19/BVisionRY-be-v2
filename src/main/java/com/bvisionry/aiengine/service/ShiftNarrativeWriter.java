package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.ShiftNarrativeResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for one pillar's qualitative shift
 * narrative (spec §6). Same shape as {@link PillarEvaluator}: the typed return
 * drives structured output, {@link Result} carries token usage + finish reason
 * for the call log.
 *
 * <p>The argument is the user message and contains ONLY the pillar name and the
 * before/after text blocks — never a score. See
 * {@code ShiftNarrativeService.userMessage}.
 */
public interface ShiftNarrativeWriter {
    Result<ShiftNarrativeResult> write(String beforeAndAfterText);
}
