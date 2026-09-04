package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * WHOLE-CALENDAR replace (spec §6.1): whatever this body says the coach's
 * availability is, is what it becomes. A partial-patch shape would need a
 * per-row identity the editor does not have — the UI edits a week, not rows —
 * and would leave "deleted" as a second way to say the same thing.
 *
 * <p>Bean validation covers only the shapes a regex can judge. The rules the
 * caller cannot express here — the zone must resolve through {@code ZoneId},
 * {@code endTime > startTime}, and no two windows on one weekday may overlap —
 * are asserted in {@code CoachScheduleService}.
 */
public record UpsertAvailabilityRequest(
        @NotBlank @Size(max = 64) String timeZone,
        @NotNull @Size(max = 100) List<@Valid RuleUpsert> rules,
        @NotNull @Size(max = 200) List<@Valid BlockUpsert> blocks) {

    public record RuleUpsert(
            @Min(1) @Max(7) int weekday,
            @NotBlank String startTime,
            @NotBlank String endTime) {}

    public record BlockUpsert(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Size(max = 200) String reason) {}
}
