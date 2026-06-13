package com.bvisionry.reporting.dto;

import java.math.BigDecimal;

public record PlatformAnalyticsResponse(
        long totalOrganizations,
        long totalUsers,
        long totalSubmissions,
        long evaluatedSubmissions,
        BigDecimal completionRate,
        BigDecimal averageOverallScore,
        long premiumOrganizations,
        long freeOrganizations
) {}
