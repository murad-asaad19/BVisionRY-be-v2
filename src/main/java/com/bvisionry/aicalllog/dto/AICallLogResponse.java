package com.bvisionry.aicalllog.dto;

import com.bvisionry.aicalllog.entity.AICallLog;
import com.bvisionry.common.enums.AICallStatus;

import java.time.Instant;
import java.util.UUID;

public record AICallLogResponse(
        UUID id,
        String callType,
        String pillarName,
        UUID submissionId,
        UUID pipelineId,
        String requestId,
        String model,
        Instant calledAt,
        int elapsedMs,
        String systemPrompt,
        String userMessage,
        String rawResponse,
        String errorMessage,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheCreationTokens,
        Integer cacheReadTokens,
        AICallStatus status
) {
    public static AICallLogResponse from(AICallLog e) {
        return new AICallLogResponse(
                e.getId(),
                e.getCallType(),
                e.getPillarName(),
                e.getSubmissionId(),
                e.getPipelineId(),
                e.getRequestId(),
                e.getModel(),
                e.getCalledAt(),
                e.getElapsedMs(),
                e.getSystemPrompt(),
                e.getUserMessage(),
                e.getRawResponse(),
                e.getErrorMessage(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getCacheCreationTokens(),
                e.getCacheReadTokens(),
                e.getStatus()
        );
    }
}
