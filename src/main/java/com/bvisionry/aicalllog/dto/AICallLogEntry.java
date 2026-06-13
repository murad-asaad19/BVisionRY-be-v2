package com.bvisionry.aicalllog.dto;

import com.bvisionry.common.enums.AICallStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-call record written fire-and-forget by {@code OpenRouterChatService} and
 * persisted by {@code AICallLogService}. Populated from both success and
 * failure paths — on failure, {@code rawResponse} is null and
 * {@code errorMessage} carries the exception.
 */
public record AICallLogEntry(
        String callType,
        String pillarName,
        UUID submissionId,
        UUID pipelineId,
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
) {}
