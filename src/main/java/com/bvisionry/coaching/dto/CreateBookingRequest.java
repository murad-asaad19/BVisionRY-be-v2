package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST} and {@code PUT /api/my/program/tasks/{taskId}/booking}
 * (spec §6.3) — booking and moving a booking are the same two choices.
 * Only the two values the member actually chose — the task comes from the path
 * and the member from the session, so neither can be named by the request.
 * {@code startsAt} is re-derived from the coach's calendar server-side; a value
 * the engine does not offer is a 400.
 */
public record CreateBookingRequest(@NotNull UUID coachId, @NotNull Instant startsAt) {}
