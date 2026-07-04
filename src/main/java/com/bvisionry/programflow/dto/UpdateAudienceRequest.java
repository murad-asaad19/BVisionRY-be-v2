package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.AudienceMode;

import jakarta.validation.constraints.NotNull;

public record UpdateAudienceRequest(
        @NotNull AudienceMode mode,
        List<UUID> teamIds,
        List<UUID> memberIds) {

    public UpdateAudienceRequest {
        teamIds = teamIds == null ? List.of() : teamIds;
        memberIds = memberIds == null ? List.of() : memberIds;
    }
}
