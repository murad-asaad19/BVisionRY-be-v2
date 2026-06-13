package com.bvisionry.aiconfig.dto;

import java.util.List;

public record OpenRouterModel(
        String id,
        String name,
        String description,
        Pricing pricing,
        int contextLength
) {

    public record Pricing(
            String prompt,
            String completion
    ) {}

    /**
     * Wrapper for the OpenRouter /models API response.
     */
    public record OpenRouterModelsResponse(
            List<OpenRouterModelData> data
    ) {}

    public record OpenRouterModelData(
            String id,
            String name,
            String description,
            OpenRouterPricing pricing,
            int context_length
    ) {}

    public record OpenRouterPricing(
            String prompt,
            String completion
    ) {}
}
