package com.bvisionry.aiengine.service;

import com.bvisionry.common.dto.MemberGrowthSummaryResult;
import dev.langchain4j.service.Result;

/**
 * Declarative LangChain4j AI service for a founder's overall growth summary
 * (spec §3). Same shape as {@link ShiftNarrativeWriter}: the typed return drives
 * structured output, {@link Result} carries token usage + finish reason for the
 * call log.
 *
 * <p>The argument is the user message and contains ONLY the founder's APPROVED
 * per-pillar narratives — never a score, never facilitator feedback. See
 * {@code MemberGrowthSummaryService.userMessage}.
 */
public interface MemberGrowthSummaryWriter {
    Result<MemberGrowthSummaryResult> write(String approvedNarratives);
}
