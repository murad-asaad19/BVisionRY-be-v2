package com.bvisionry.insights.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A funder-presentable outcomes report for one cohort on one assessment
 * (roadmap §7 item 16): where the cohort started, where it is now, and what
 * each founder moved.
 *
 * <p>Deliberately id-free. Everything here is meant to be read outside the
 * organization ({@code roi_report_audience: FUNDER}), so the payload carries
 * names and numbers only — no organization, cohort, pipeline, submission or
 * user identifiers, and no email addresses.
 *
 * <p>Counts are three different populations and the report names all three,
 * because a delta over an unnamed population is not evidence:
 * <ul>
 *   <li>{@code cohortSize} — everyone enrolled in the cohort;</li>
 *   <li>{@code foundersMeasured} — those with at least one evaluated
 *       assessment;</li>
 *   <li>{@code foundersRemeasured} — those with at least two, which is the
 *       population every pillar average and delta is computed over.</li>
 * </ul>
 *
 * <p>{@code periodStart}/{@code periodEnd} bound the measurement window: the
 * earliest intake and the latest assessment actually on record. Both are
 * {@code null} when nobody has been assessed. {@code completionRate} is the
 * percentage of the cohort's assigned LIVE module tasks that were submitted,
 * or {@code null} when the cohort has no assigned tasks at all.
 */
public record RoiReportResponse(
        String organizationName,
        String programName,
        String assessmentName,
        LocalDate periodStart,
        LocalDate periodEnd,
        int cohortSize,
        int foundersMeasured,
        int foundersRemeasured,
        int tasksAssigned,
        int tasksCompleted,
        BigDecimal completionRate,
        List<RoiPillarMovementDto> pillars,
        List<RoiFounderDeltaDto> founders,
        Instant generatedAt
) {}
