package com.bvisionry.coaching.dto;

import java.util.UUID;

import com.bvisionry.coaching.repository.CoachingReadRepository.RosterRow;

/**
 * One founder on the coach's roster: name (no email — {@code coach_sees} does
 * not include contact data), the GRANTED cohorts they belong to, and
 * grant-scoped program completion. {@code completionPct} is null when nothing
 * is assigned — "no tasks yet" is different from 0%.
 */
public record CoachFounderSummary(
        UUID id,
        String name,
        String cohortNames,
        int totalTasks,
        int submittedTasks,
        Integer completionPct) {

    public static CoachFounderSummary from(RosterRow row) {
        return new CoachFounderSummary(row.id(), row.name(), row.cohortNames(),
                row.totalTasks(), row.submittedTasks(),
                row.totalTasks() == 0 ? null
                        : Math.round(100f * row.submittedTasks() / row.totalTasks()));
    }
}
