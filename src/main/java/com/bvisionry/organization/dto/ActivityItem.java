package com.bvisionry.organization.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActivityItem(
        UUID id,
        Instant timestamp,
        UUID actorId,
        String actorName,
        String action,
        String summary,
        Map<String, Object> details
) {}
