package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of the two cohort-wide scheduling routes (spec v2 §6.1 / §6.2).
 *
 * <p>{@code coachId} is the super admin's pick from
 * {@link CohortSessionSchedulingResponse#coaches()} and is IGNORED on the coach
 * route, where the scheduler is the caller — a coach cannot name someone else's
 * calendar, so the field is optional rather than a second request type.
 * {@code startsAt} is re-derived from that coach's availability server-side; an
 * instant the engine does not offer is a 400.
 */
public record ScheduleSessionRequest(UUID coachId, @NotNull Instant startsAt) {}
