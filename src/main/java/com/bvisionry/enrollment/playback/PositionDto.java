package com.bvisionry.enrollment.playback;

/**
 * Response for the video resume position endpoint.
 *
 * @param positionSeconds last saved playback position in seconds
 * @param watchedPct      percentage of the video watched (0–100)
 * @param completed       whether the content item is marked complete
 */
public record PositionDto(
        int positionSeconds,
        int watchedPct,
        boolean completed) {
}
