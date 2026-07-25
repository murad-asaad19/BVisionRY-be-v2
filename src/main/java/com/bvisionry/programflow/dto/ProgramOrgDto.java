package com.bvisionry.programflow.dto;

import java.util.UUID;

/** A sub-organization as the Program Flow / Workshops org switchers see it. */
public record ProgramOrgDto(
        UUID id,
        String name,
        String description,
        String parentName,
        int memberCount,
        int cohortCount,
        int workshopCount,
        boolean inProgramFlow,
        boolean inWorkshops) {
}
