package com.bvisionry.businesscard.dto;

import com.bvisionry.businesscard.entity.CardLink;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One link button, used both inbound (admin create/update body) and outbound
 * (admin + public responses). {@code icon} is a free string key the frontend
 * maps to a glyph; the service upper-cases it and defaults blanks to "LINK".
 */
public record BusinessCardLinkPayload(
        @Size(max = 32, message = "Icon key must be 32 characters or less")
        String icon,

        @NotBlank(message = "Link label is required")
        @Size(max = 80, message = "Link label must be 80 characters or less")
        String label,

        @NotBlank(message = "Link URL is required")
        @Size(max = 512, message = "Link URL must be 512 characters or less")
        String url
) {
    public static BusinessCardLinkPayload from(CardLink link) {
        return new BusinessCardLinkPayload(link.getIcon(), link.getLabel(), link.getUrl());
    }

    public static List<BusinessCardLinkPayload> fromAll(List<CardLink> links) {
        return links == null ? List.of() : links.stream().map(BusinessCardLinkPayload::from).toList();
    }
}
