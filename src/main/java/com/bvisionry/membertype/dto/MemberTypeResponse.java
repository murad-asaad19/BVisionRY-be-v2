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
        /**
         * Members currently assigned this type. Mirrors the delete guard:
         * > 0 (or a system type) means delete is refused, so the UI can
         * disable the control up front instead of failing on click.
         */
        long inUseCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static MemberTypeResponse from(MemberType t) {
        return from(t, 0);
    }

    public static MemberTypeResponse from(MemberType t, long inUseCount) {
        return new MemberTypeResponse(
                t.getId(), t.getCode(), t.getLabel(), t.getDisplayOrder(),
                t.isSystem(), inUseCount, t.getCreatedAt(), t.getUpdatedAt());
    }
}
