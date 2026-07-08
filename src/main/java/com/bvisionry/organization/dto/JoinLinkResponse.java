package com.bvisionry.organization.dto;

import com.bvisionry.organization.entity.JoinLink;

import java.time.Instant;
import java.util.UUID;

public record JoinLinkResponse(
        UUID id, UUID token, UUID organizationId, UUID workshopId, boolean isActive,
        Instant expiresAt, Instant createdAt
) {
    public static JoinLinkResponse from(JoinLink link) {
        return new JoinLinkResponse(link.getId(), link.getToken(),
                link.getOrganization().getId(), link.getWorkshopId(), link.isActive(),
                link.getExpiresAt(), link.getCreatedAt());
    }
}
