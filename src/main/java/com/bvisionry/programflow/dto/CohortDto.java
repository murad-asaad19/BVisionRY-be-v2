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
        List<UUID> memberIds,
        /**
         * The assigned orgs' names (spec §13). Cohort names are no longer
         * unique per org, so the platform switcher needs this to tell two
         * "Cohort 1"s apart. Empty on the org participation view — there the
         * org is the page you are already on.
         */
        List<String> orgNames) {

    public static CohortDto of(Cohort c) {
        return of(c, List.of());
    }

    public static CohortDto of(Cohort c, List<String> orgNames) {
        return new CohortDto(c.getId(), c.getName(), c.getPosition(), c.getStatus(),
                c.getLaunchedAt(), c.getCompletedAt(), c.getArchivedAt(),
                List.copyOf(c.getMemberIds()), List.copyOf(orgNames));
    }
}
