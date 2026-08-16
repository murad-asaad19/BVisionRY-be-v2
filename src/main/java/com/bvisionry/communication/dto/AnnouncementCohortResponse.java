package com.bvisionry.communication.dto;

import java.util.UUID;

/**
 * A cohort the caller may broadcast to: every cohort in the org for an admin,
 * only their granted cohorts for a coach. The picker is server-derived so the
 * UI can never offer a target the post endpoint would refuse.
 *
 * <p>{@code status} is the cohort's lifecycle state — {@code DRAFT} or
 * {@code LAUNCHED}. Only a LAUNCHED cohort is member-visible
 * ({@code CohortVisibility.MEMBER_VISIBLE}), so only a LAUNCHED cohort can
 * receive a broadcast; carrying the state here lets the picker's composer
 * explain that up front instead of the author discovering it from
 * {@code AnnouncementService.post}'s refusal. The LIST stays deliberately
 * UNFILTERED — an unlaunched cohort keeps its announcement history and staff
 * must still be able to reach it, so this adds information, not a filter.
 *
 * <p>A {@code String} rather than {@code programflow}'s {@code CohortStatus}
 * enum: the ArchUnit ratchet forbids new feature→feature imports — the same
 * reason {@link com.bvisionry.communication.repository.AnnouncementReadRepository}
 * reads cohorts as raw SQL — and it is the shape {@code cohortview}'s DTOs
 * already use for this column.
 */
public record AnnouncementCohortResponse(UUID id, String name, String status) {
}
