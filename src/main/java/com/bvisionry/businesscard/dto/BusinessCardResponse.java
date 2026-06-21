package com.bvisionry.businesscard.dto;

import com.bvisionry.businesscard.entity.BusinessCard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin-facing view. {@code photoUrl} is the raw stored value (a {@code minio://}
 * marker or external URL) so it round-trips back into the edit form unchanged,
 * while {@code photoDisplayUrl} is the resolved, browser-loadable URL for preview.
 */
public record BusinessCardResponse(
        UUID id,
        String slug,
        String name,
        String title,
        String tagline,
        String taglineBold,
        String photoUrl,
        String photoDisplayUrl,
        List<BusinessCardLinkPayload> links,
        boolean published,
        int displayOrder,
        Instant createdAt,
        Instant updatedAt
) {
    public static BusinessCardResponse from(BusinessCard c, String photoDisplayUrl) {
        return new BusinessCardResponse(
                c.getId(), c.getSlug(), c.getName(), c.getTitle(), c.getTagline(),
                c.getTaglineBold(), c.getPhotoUrl(), photoDisplayUrl,
                BusinessCardLinkPayload.fromAll(c.getLinks()), c.isPublished(),
                c.getDisplayOrder(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
