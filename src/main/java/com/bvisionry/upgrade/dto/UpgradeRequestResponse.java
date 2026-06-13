package com.bvisionry.upgrade.dto;

import com.bvisionry.upgrade.entity.UpgradeFeatureContext;
import com.bvisionry.upgrade.entity.UpgradeRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Single shape used by both POST (creation result) and GET-latest responses,
 * so the FE renders the same gate cooldown panel regardless of which endpoint
 * loaded the data.
 */
public record UpgradeRequestResponse(
        UUID id,
        Instant requestedAt,
        Instant cooldownEndsAt,
        UpgradeFeatureContext featureContext,
        String note
) {
    public static UpgradeRequestResponse from(UpgradeRequest r, Duration cooldown) {
        return new UpgradeRequestResponse(
                r.getId(),
                r.getCreatedAt(),
                r.getCreatedAt().plus(cooldown),
                r.getFeatureContext(),
                r.getNote()
        );
    }
}
