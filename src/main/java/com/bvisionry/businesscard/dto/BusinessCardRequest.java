package com.bvisionry.businesscard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create / update body for a business card. The admin edit form always submits
 * the complete record, so a blank optional field is an explicit clear. A blank
 * {@code slug} is auto-derived from {@code name} on create and left unchanged on
 * update (so an existing QR link never silently breaks).
 */
public record BusinessCardRequest(
        @Size(max = 80, message = "Slug must be 80 characters or less")
        String slug,

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must be 160 characters or less")
        String name,

        @Size(max = 200, message = "Title must be 200 characters or less")
        String title,

        @Size(max = 2000, message = "Tagline must be 2000 characters or less")
        String tagline,

        @Size(max = 120, message = "Bold phrase must be 120 characters or less")
        String taglineBold,

        @Size(max = 512, message = "Photo URL must be 512 characters or less")
        String photoUrl,

        @Valid
        List<BusinessCardLinkPayload> links,

        Boolean published,

        Integer displayOrder
) {}
