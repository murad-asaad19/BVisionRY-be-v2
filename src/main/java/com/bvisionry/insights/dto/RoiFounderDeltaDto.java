package com.bvisionry.insights.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One founder's row of the ROI report: their overall readiness at intake, at
 * their latest assessment, and the move between the two.
 *
 * <p>The scores are that submission's canonical overall readiness score
 * (0–100) — the same number the founder sees on their own results, not a mean
 * recomputed here. A founder with fewer than two evaluated assessments has
 * {@code latestOn},
 * {@code latestScore}, {@code delta} and {@code direction} all {@code null} —
 * intake only, never a fabricated delta. A founder never assessed has
 * {@code assessments = 0} and no scores at all; they still count towards the
 * cohort's size and completion, because they are in the cohort.
 *
 * <p>Funder-facing by design ({@code roi_report_audience: FUNDER}): the founder
 * is named, and nothing else identifies them — no email, no user id.
 */
public record RoiFounderDeltaDto(
        String founderName,
        int assessments,
        LocalDate intakeOn,
        BigDecimal intakeScore,
        LocalDate latestOn,
        BigDecimal latestScore,
        BigDecimal delta,
        MovementDirection direction,
        int tasksAssigned,
        int tasksCompleted,
        BigDecimal completionRate
) {}
