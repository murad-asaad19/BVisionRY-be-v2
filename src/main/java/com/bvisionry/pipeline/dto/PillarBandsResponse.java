package com.bvisionry.pipeline.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One scored pillar's maturity bands: label -> inclusive {@code [min, max]}
 * score range.
 *
 * <p>Bands are per-pillar configuration, so a maturity label ("Strong") means
 * nothing without the pillar's own ranges. This is the read that lets the party
 * being measured see the yardstick; it carries no authoring fields.
 */
public record PillarBandsResponse(
        UUID pillarId,
        String pillarName,
        Map<String, List<Integer>> maturityThresholds
) {}
