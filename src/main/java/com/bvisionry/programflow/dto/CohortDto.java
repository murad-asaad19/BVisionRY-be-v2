package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;

/** A cohort as the admin switcher/roster sees it. */
public record CohortDto(
        UUID id,
        String name,
        int position,
        CohortStatus status,
        List<UUID> memberIds) {

    public static CohortDto of(Cohort c) {
        return new CohortDto(c.getId(), c.getName(), c.getPosition(), c.getStatus(),
                List.copyOf(c.getMemberIds()));
    }
}
