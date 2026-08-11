package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.AudienceMode;

import jakarta.validation.constraints.NotNull;

public record UpdateAudienceRequest(
        @NotNull AudienceMode mode,
        List<UUID> memberIds) {

    public UpdateAudienceRequest {
        memberIds = memberIds == null ? List.of() : memberIds;
    }
}
