package com.bvisionry.organization.dto;

import java.time.Instant;
import java.util.List;

public record DashboardResponse(
        KpiBlock kpis,
        List<AttentionItem> attention,
        TierMix tierMix,
        Instant generatedAt
) {}
