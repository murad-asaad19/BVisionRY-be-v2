package com.bvisionry.coaching.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/coach/sessions/{id}/complete} (spec v2 §6.1):
 * the coach's roll call. The session becomes COMPLETED with EXACTLY these
 * members marked present — held-but-absent is COMPLETED with no attendance
 * row, which is what makes participation count the miss (NO_SHOW is gone).
 *
 * <p>{@code @NotNull} on a list that may be EMPTY on purpose: nobody turned up
 * is a real answer, a missing field is a 400.
 */
public record CompleteSessionRequest(@NotNull List<UUID> presentMemberIds) {}
