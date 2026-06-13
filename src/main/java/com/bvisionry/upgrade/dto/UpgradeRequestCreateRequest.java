package com.bvisionry.upgrade.dto;

import com.bvisionry.upgrade.entity.UpgradeFeatureContext;
import jakarta.validation.constraints.Size;

public record UpgradeRequestCreateRequest(
        UpgradeFeatureContext featureContext,
        @Size(max = 500, message = "Note cannot exceed 500 characters") String note
) {}
