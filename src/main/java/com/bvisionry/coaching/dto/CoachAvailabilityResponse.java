package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The coach's calendar as they edit it — {@code GET/PUT /api/v1/coach/availability}
 * (spec §6.1).
 *
 * <p>Rule times are {@code "HH:mm"} STRINGS on the wire, not instants: they are
 * wall-clock in {@code timeZone} and only become instants once the slot engine
 * lands them on a date. Blocks are the opposite — a holiday is real time, so
 * they travel as instants.
 *
 * @param timeZone IANA zone id, null until the coach has ever saved
 */
public record CoachAvailabilityResponse(String timeZone, List<AvailabilityRuleDto> rules,
                                        List<AvailabilityBlockDto> blocks) {

    /** One weekly window. {@code weekday}: ISO 1 = Monday … 7 = Sunday. */
    public record AvailabilityRuleDto(UUID id, int weekday, String startTime, String endTime) {}

    /** One blackout span. */
    public record AvailabilityBlockDto(UUID id, Instant startsAt, Instant endsAt, String reason) {}
}
