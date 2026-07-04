package com.bvisionry.programflow.dto;

import java.util.UUID;

import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;

/** A cohort as the learner switcher sees it (name + lifecycle only). */
public record LearnerCohortDto(
        UUID id,
        String name,
        CohortStatus status) {

    public static LearnerCohortDto of(Cohort c) {
        return new LearnerCohortDto(c.getId(), c.getName(), c.getStatus());
    }
}
