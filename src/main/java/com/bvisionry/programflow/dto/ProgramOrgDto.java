package com.bvisionry.programflow.dto;

import java.util.UUID;

/** An organization as the Program Flow org switcher / add-picker sees it. */
public record ProgramOrgDto(
        UUID id,
        String name,
        String description,
        int memberCount,
        int cohortCount) {
}
