package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Sprint leaderboard, sorted by points descending. */
public record LeaderboardResponse(
        String endLabel,
        OffsetDateTime endAt,
        List<Row> rows) {

    public record Row(
            UUID userId,
            String name,
            String teamName,
            int points,
            int streak,
            boolean me) {
    }
}
