package com.bvisionry.reporting.dto;

public record PremiumErrorResponse(
        String error,
        String feature,
        String message
) {
    public static PremiumErrorResponse of(String feature) {
        return new PremiumErrorResponse(
                "premium_required",
                feature,
                "This feature requires a Premium subscription. Upgrade your organization to access " + feature + "."
        );
    }
}
