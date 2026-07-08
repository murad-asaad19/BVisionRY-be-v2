package com.bvisionry.workshops.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin analytics: aggregates + the completion log (newest first). */
public record WorkshopAnalyticsResponse(
        long completed,
        long totalMs,
        long avgMs,
        List<Row> rows) {

    public record Row(
            UUID id,
            String exerciseTitle,
            String taskTitle,
            String taskType,
            String role,
            String userName,
            String teamName,
            Integer attempts,
            Long elapsedMs,
            Instant completedAt) {
    }
}
