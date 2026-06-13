package com.bvisionry.evaluation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Admin request to unlock one or more pillars on an evaluated submission.
 * Reason is optional; we keep it short so it can be displayed inline in the
 * member's banner ("Admin unlocked 2 pillars: <reason>").
 */
public record UnlockPillarsRequest(
        @NotEmpty(message = "Select at least one pillar to unlock")
        List<UUID> pillarIds,
        @Size(max = 500, message = "Reason is too long")
        String reason
) {}
