package com.bvisionry.insights.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.insights.RoiReportReadRepository;
import com.bvisionry.insights.RoiReportReadRepository.FounderRow;
import com.bvisionry.insights.RoiReportReadRepository.PillarMovementRow;
import com.bvisionry.insights.RoiReportReadRepository.PillarRef;
import com.bvisionry.insights.RoiReportReadRepository.ReportHeader;
import com.bvisionry.insights.dto.MovementDirection;
import com.bvisionry.insights.dto.RoiFounderDeltaDto;
import com.bvisionry.insights.dto.RoiPillarMovementDto;
import com.bvisionry.insights.dto.RoiReportResponse;

import lombok.RequiredArgsConstructor;

/**
 * Assembles the cohort ROI report. All scoping and the intake/latest grain live
 * in {@link RoiReportReadRepository}'s SQL; this class resolves the 404 guard
 * and does the arithmetic the report is judged on — deltas, direction, the
 * three population counts, the measurement window and the completion rate.
 *
 * <p>Every derived number is either evidenced or {@code null}. There is no
 * branch anywhere below that substitutes a default for a missing measurement.
 */
@Service
@RequiredArgsConstructor
public class RoiReportService {

    private final RoiReportReadRepository repository;

    /**
     * One read transaction across all four queries. Under autocommit they are
     * four independent snapshots, so an evaluation landing mid-report could
     * make {@code foundersRemeasured} and {@code foundersPaired} describe
     * different populations — an internally inconsistent document, and the one
     * kind of error a funder-facing report cannot afford.
     */
    @Transactional(readOnly = true)
    public RoiReportResponse report(UUID orgId, UUID cohortId, UUID pipelineId) {
        // An absent/foreign cohort and an absent/unpublished pipeline both read
        // as absent — one 404, no existence oracle for either.
        ReportHeader header = repository.header(orgId, cohortId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        return assemble(header,
                repository.pillars(pipelineId),
                repository.pillarMovement(orgId, cohortId, pipelineId),
                repository.founders(orgId, cohortId, pipelineId),
                Instant.now());
    }

    /** Pure assembly — unit-tested on the delta, population and empty cases. */
    static RoiReportResponse assemble(ReportHeader header, List<PillarRef> pillars,
                                      List<PillarMovementRow> movement, List<FounderRow> founders,
                                      Instant generatedAt) {
        Map<UUID, PillarMovementRow> byPillar = movement.stream()
                .collect(Collectors.toMap(PillarMovementRow::pillarId, Function.identity()));

        List<RoiPillarMovementDto> pillarRows = pillars.stream()
                .map(p -> movementRow(p, byPillar.get(p.id())))
                .toList();
        List<RoiFounderDeltaDto> founderRows = founders.stream()
                .map(RoiReportService::founderRow)
                .toList();

        int tasksAssigned = founders.stream().mapToInt(FounderRow::tasksAssigned).sum();
        int tasksCompleted = founders.stream().mapToInt(FounderRow::tasksCompleted).sum();

        return new RoiReportResponse(
                header.organizationName(), header.programName(), header.assessmentName(),
                earliestIntake(founders),
                latestAssessment(founders),
                founders.size(),
                (int) founders.stream().filter(f -> f.assessments() >= 1).count(),
                (int) founders.stream().filter(f -> f.assessments() >= 2).count(),
                tasksAssigned, tasksCompleted, percentage(tasksCompleted, tasksAssigned),
                pillarRows, founderRows, generatedAt);
    }

    /** A pillar nobody was measured on twice keeps its name and nothing else. */
    private static RoiPillarMovementDto movementRow(PillarRef pillar, PillarMovementRow row) {
        if (row == null) {
            return new RoiPillarMovementDto(pillar.name(), 0, null, null, null, null);
        }
        BigDecimal delta = delta(row.intakeAverage(), row.latestAverage());
        return new RoiPillarMovementDto(pillar.name(), row.pairedFounders(),
                row.intakeAverage(), row.latestAverage(), delta, direction(delta));
    }

    private static RoiFounderDeltaDto founderRow(FounderRow row) {
        BigDecimal delta = delta(row.intakeScore(), row.latestScore());
        return new RoiFounderDeltaDto(row.founderName(), row.assessments(),
                row.intakeOn(), row.intakeScore(), row.latestOn(), row.latestScore(),
                delta, direction(delta),
                row.tasksAssigned(), row.tasksCompleted(),
                percentage(row.tasksCompleted(), row.tasksAssigned()));
    }

    /** Latest minus intake, or null when either end is missing. */
    private static BigDecimal delta(BigDecimal intake, BigDecimal latest) {
        if (intake == null || latest == null) {
            return null;
        }
        return latest.subtract(intake).setScale(1, RoundingMode.HALF_UP);
    }

    private static MovementDirection direction(BigDecimal delta) {
        if (delta == null) {
            return null;
        }
        int sign = delta.signum();
        return sign > 0 ? MovementDirection.UP
                : sign < 0 ? MovementDirection.DOWN
                : MovementDirection.FLAT;
    }

    /** {@code done} as a percentage of {@code total}, or null when nothing is assigned. */
    private static BigDecimal percentage(int done, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(done * 100L)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static LocalDate earliestIntake(List<FounderRow> founders) {
        return founders.stream().map(FounderRow::intakeOn).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * The window closes at the most recent assessment on record — a founder
     * measured once contributes their intake date, since that IS their latest.
     */
    private static LocalDate latestAssessment(List<FounderRow> founders) {
        return founders.stream()
                .map(f -> f.latestOn() != null ? f.latestOn() : f.intakeOn())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }
}
