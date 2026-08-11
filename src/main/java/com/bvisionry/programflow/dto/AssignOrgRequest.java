package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Assigns an org to a platform cohort (spec §13.3). Enrollment now:
 * {@code enrollAllMembers} takes every active member, else the picked
 * {@code memberIds}. {@code autoEnroll} keeps enrolling members who join the
 * org later — the "auto assignation" mode.
 */
public record AssignOrgRequest(
        @NotNull UUID orgId,
        boolean enrollAllMembers,
        List<UUID> memberIds,
        boolean autoEnroll) {

    public AssignOrgRequest {
        memberIds = memberIds == null ? List.of() : memberIds;
    }
}
