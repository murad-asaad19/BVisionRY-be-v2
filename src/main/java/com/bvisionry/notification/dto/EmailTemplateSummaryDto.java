package com.bvisionry.notification.dto;

import com.bvisionry.notification.entity.EmailTemplateKey;

import java.time.Instant;

/** Lightweight view for the admin list page — no body payload. */
public record EmailTemplateSummaryDto(
        EmailTemplateKey key,
        String displayName,
        String description,
        boolean customized,
        Instant updatedAt
) {}
