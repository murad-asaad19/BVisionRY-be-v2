package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;

/** A cohort as the admin switcher/roster sees it, with the §7b launch stamp. */
public record CohortDto(
        UUID id,
        String name,
        int position,
        CohortStatus status,
        OffsetDateTime launchedAt,
        List<UUID> memberIds,
        /**
         * The assigned orgs' names (spec §13). Cohort names are no longer
         * unique per org, so the platform switcher needs this to tell two
         * "Cohort 1"s apart. Empty on the org participation view — there the
         * org is the page you are already on.
         */
        List<String> orgNames,
        /**
         * Curriculum size + stage vocabulary for the cohort-card progress bar
         * (spec §8; web {@code cohortProgress()}). Only {@link
         * com.bvisionry.programflow.web.CohortService#listAssigned} — the org
         * participation list these cards render from — looks these up for
         * real; every other path below returns the neutral 0/"Week" default,
         * either because the cohort genuinely has no modules yet (a fresh
         * draft) or because its response is never written into the cards'
         * query cache (a mutation always triggers a refetch instead, see
         * `roster-dialog.tsx`), so a stale count here can't reach the UI.
         */
        int moduleCount,
        String stageLabel) {

    public static CohortDto of(Cohort c) {
        return of(c, List.of());
    }

    public static CohortDto of(Cohort c, List<String> orgNames) {
        return new CohortDto(c.getId(), c.getName(), c.getPosition(), c.getStatus(),
                c.getLaunchedAt(),
                List.copyOf(c.getMemberIds()), List.copyOf(orgNames), 0, "Week");
    }
}
