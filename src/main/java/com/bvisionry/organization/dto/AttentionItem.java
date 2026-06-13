package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.AttentionSeverity;

import java.time.Instant;
import java.util.UUID;

public record AttentionItem(
        String code,
        AttentionSeverity severity,
        UUID orgId,
        String orgName,
        String headline,
        String detail,
        String ctaLabel,
        Instant since
) {}
