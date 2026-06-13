package com.bvisionry.enrollment.playback;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code POST …/position}: the current playback position and total
 * duration of the content item, both in seconds.
 */
public record UpdatePositionRequest(
        @NotNull @Min(0) Integer positionSeconds,
        @NotNull @Min(1) Integer durationSeconds) {
}
