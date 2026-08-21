package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * An organization's inactivity-nudge window, read and written by the same
 * shape (as {@code ProgramSettingsDto} does for its cohort). One knob, so one
 * record rather than a request/response pair that could never disagree.
 *
 * <p>{@code @NotNull Integer}, NOT a primitive {@code int}. A primitive binds
 * an absent or null field to {@code 0} — which passes {@code @Min(0)} AND is
 * the org-wide off switch, so {@code PUT {}} would 200 and silently kill an
 * org's nudges with nothing in the request naming that intent. Switching
 * nudging off has to be asked for, not defaulted into.
 * ({@code ProgramSettingsDto} uses a primitive and is only accidentally safe:
 * its {@code @Min(1)} happens to reject the value its absence produces.)
 *
 * @param inactivityNudgeDays days without progress on an active course
 *                            enrolment before the founder is nudged;
 *                            {@code 0} switches nudging off for the whole org.
 *                            The 90 cap mirrors the CHECK in
 *                            {@code V149__inactivity_nudge_threshold.sql}; the
 *                            binding cap is derived from the notification
 *                            retention window at write time — see
 *                            {@code OrganizationService.updateNudgeSettings}.
 */
public record NudgeSettingsDto(
        @NotNull @Min(0) @Max(90) Integer inactivityNudgeDays) {
}
