package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

public record UpdateCohortMembersRequest(
        List<UUID> memberIds) {

    public UpdateCohortMembersRequest {
        memberIds = memberIds == null ? List.of() : memberIds;
    }
}
