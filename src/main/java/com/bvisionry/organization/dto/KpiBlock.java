package com.bvisionry.organization.dto;

import java.math.BigDecimal;

public record KpiBlock(
        long totalOrgs,
        long totalOrgsDelta30d,
        long activeCount,
        BigDecimal retentionPct,
        long suspendedCount,
        long suspendedDelta7d,
        long trialsExpiringSoon,
        long totalMembers,
        long totalMembersDelta7d
) {}
