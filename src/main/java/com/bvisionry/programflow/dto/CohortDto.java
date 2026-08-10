package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;

/** A cohort as the admin switcher/roster sees it, with §7b lifecycle stamps. */
public record CohortDto(
        UUID id,
        String name,
        int position,
        CohortStatus status,
        OffsetDateTime launchedAt,
        OffsetDateTime completedAt,
        OffsetDateTime archivedAt,
        List<UUID> memberIds) {

    public static CohortDto of(Cohort c) {
        return new CohortDto(c.getId(), c.getName(), c.getPosition(), c.getStatus(),
                c.getLaunchedAt(), c.getCompletedAt(), c.getArchivedAt(),
                List.copyOf(c.getMemberIds()));
    }
}
