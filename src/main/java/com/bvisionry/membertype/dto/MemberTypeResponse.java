package com.bvisionry.membertype.dto;

import com.bvisionry.membertype.entity.MemberType;

import java.time.Instant;
import java.util.UUID;

public record MemberTypeResponse(
        UUID id,
        String code,
        String label,
        int displayOrder,
        boolean isSystem,
        Instant createdAt,
        Instant updatedAt
) {
    public static MemberTypeResponse from(MemberType t) {
        return new MemberTypeResponse(
                t.getId(), t.getCode(), t.getLabel(), t.getDisplayOrder(),
                t.isSystem(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
