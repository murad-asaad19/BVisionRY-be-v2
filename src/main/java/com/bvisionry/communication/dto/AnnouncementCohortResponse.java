package com.bvisionry.communication.dto;

import java.util.UUID;

/**
 * A cohort the caller may broadcast to: every cohort in the org for an admin,
 * only their granted cohorts for a coach. The picker is server-derived so the
 * UI can never offer a target the post endpoint would refuse.
 */
public record AnnouncementCohortResponse(UUID id, String name) {
}
