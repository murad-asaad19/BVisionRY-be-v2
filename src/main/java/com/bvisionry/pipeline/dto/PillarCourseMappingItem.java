package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One rule as the admin declares it: a band POSITION within this pillar's own
 * band set (0 = weakest), and the course it points at.
 *
 * <p>The upper bound on {@code bandPosition} is not expressible here — it is
 * the pillar's current band count, which is mutable data — so the service
 * checks it against the pillar being written.
 */
public record PillarCourseMappingItem(
        @NotNull @Min(0) Integer bandPosition,
        @NotNull UUID courseId
) {}
