package com.bvisionry.organization.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One course a member is (or was) on, as the org admin's member drawer shows it.
 *
 * <p>Removed courses are INCLUDED, flagged by {@code removed}. An admin who has
 * just taken a founder off a module needs to see that it happened — and a list
 * that silently drops the row reads exactly like a list that failed to save.
 *
 * @param recommendedForPillar the pillar whose weak band auto-enrolled them, or
 *        {@code null} when they enrolled themselves. ONE true reason among
 *        possibly several: the ledger records a course against whichever pillar
 *        asked for it first, so this must never be phrased as <em>the</em> reason
 *        (the same constraint the founder-facing copy carries).
 * @param removedReason what the admin typed when removing them, if anything.
 */
public record MemberCourseResponse(
        UUID courseId,
        String courseTitle,
        String courseSlug,
        String status,
        int progressPct,
        OffsetDateTime enrolledAt,
        OffsetDateTime completedAt,
        String recommendedForPillar,
        boolean removed,
        OffsetDateTime removedAt,
        String removedReason
) {}
