package com.bvisionry.businesscard.dto;

import com.bvisionry.businesscard.entity.BusinessCard;

import java.util.List;

/**
 * Public-site view. {@code photoUrl} is the resolved, browser-loadable URL — the
 * internal storage marker is never exposed on the unauthenticated endpoint.
 */
public record PublicBusinessCardResponse(
        String slug,
        String name,
        String title,
        String tagline,
        String taglineBold,
        String photoUrl,
        List<BusinessCardLinkPayload> links
) {
    public static PublicBusinessCardResponse from(BusinessCard c, String resolvedPhotoUrl) {
        return new PublicBusinessCardResponse(
                c.getSlug(), c.getName(), c.getTitle(), c.getTagline(),
                c.getTaglineBold(), resolvedPhotoUrl,
                BusinessCardLinkPayload.fromAll(c.getLinks()));
    }
}
