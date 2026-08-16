package com.bvisionry.insights.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bvisionry.insights.RoiReportReadRepository.FounderRow;
import com.bvisionry.insights.RoiReportReadRepository.PillarMovementRow;
import com.bvisionry.insights.RoiReportReadRepository.PillarRef;
import com.bvisionry.insights.RoiReportReadRepository.ReportHeader;
import com.bvisionry.insights.dto.MovementDirection;
import com.bvisionry.insights.dto.RoiReportResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic the ROI report is judged on, isolated from the SQL: deltas and
 * their direction, the three population counts, the measurement window and the
 * completion rate — plus the rule that a measurement which does not exist stays
 * null rather than defaulting to zero.
 */
class RoiReportServiceTest {

    private static final ReportHeader HEADER =
            new ReportHeader("Acme Accelerator", "Winter 2026", "Founder Readiness");
    private static final Instant AT = Instant.parse("2026-07-25T10:15:30Z");

    private static final UUID VISION = UUID.randomUUID();
    private static final UUID MARKET = UUID.randomUUID();
    private static final List<PillarRef> PILLARS =
            List.of(new PillarRef(VISION, "Vision"), new PillarRef(MARKET, "Market"));

    @Test
    void pillarDeltaIsLatestMinusIntake_withDirection() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS,
                List.of(new PillarMovementRow(VISION, 4, bd("40.0"), bd("58.5")),
                        new PillarMovementRow(MARKET, 4, bd("70.0"), bd("61.0"))),
                List.of(), AT);

        assertThat(report.pillars()).hasSize(2);
        assertThat(report.pillars().getFirst().pillarName()).isEqualTo("Vision");
        assertThat(report.pillars().getFirst().delta()).isEqualByComparingTo("18.5");
        assertThat(report.pillars().getFirst().direction()).isEqualTo(MovementDirection.UP);
        assertThat(report.pillars().getFirst().foundersPaired()).isEqualTo(4);

        // A drop is reported as a drop — the report is not a sales sheet.
        assertThat(report.pillars().get(1).delta()).isEqualByComparingTo("-9.0");
        assertThat(report.pillars().get(1).direction()).isEqualTo(MovementDirection.DOWN);
    }

    @Test
    void unchangedPillarIsFlat_notAbsent() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS,
                List.of(new PillarMovementRow(VISION, 2, bd("55.0"), bd("55.0"))),
                List.of(), AT);

        assertThat(report.pillars().getFirst().delta()).isEqualByComparingTo("0.0");
        assertThat(report.pillars().getFirst().direction()).isEqualTo(MovementDirection.FLAT);
    }

    @Test
    void pillarNobodyWasMeasuredTwiceOnCarriesNoNumbers() {
        // MARKET has no movement row at all: the axis keeps its name, and every
        // number is null — never a fabricated 0.
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS,
                List.of(new PillarMovementRow(VISION, 3, bd("40.0"), bd("44.0"))),
                List.of(), AT);

        var market = report.pillars().get(1);
        assertThat(market.pillarName()).isEqualTo("Market");
        assertThat(market.foundersPaired()).isZero();
        assertThat(market.intakeAverage()).isNull();
        assertThat(market.latestAverage()).isNull();
        assertThat(market.delta()).isNull();
        assertThat(market.direction()).isNull();
    }

    @Test
    void founderWithOneAssessmentShowsIntakeOnly_noDelta() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 1, date(1), bd("52.0"), null, null, 0, 0)), AT);

        var ada = report.founders().getFirst();
        assertThat(ada.intakeScore()).isEqualByComparingTo("52.0");
        assertThat(ada.latestScore()).isNull();
        assertThat(ada.delta()).isNull();
        assertThat(ada.direction()).isNull();
    }

    @Test
    void populationCountsSeparateEnrolled_measured_andRemeasured() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 3, date(1), bd("40.0"), date(30), bd("62.0"), 10, 7),
                        founder("Bo", 1, date(5), bd("50.0"), null, null, 10, 3),
                        founder("Cy", 0, null, null, null, null, 10, 0)), AT);

        assertThat(report.cohortSize()).isEqualTo(3);
        assertThat(report.foundersMeasured()).isEqualTo(2);
        assertThat(report.foundersRemeasured()).isEqualTo(1);
        // The never-assessed founder still counts towards the cohort and its
        // completion — they are in the program.
        assertThat(report.founders().get(2).assessments()).isZero();
        assertThat(report.founders().get(2).intakeScore()).isNull();
    }

    @Test
    void completionRateIsSubmittedOverAssigned_andNullWhenNothingAssigned() {
        RoiReportResponse withTasks = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 1, date(1), bd("40.0"), null, null, 8, 6),
                        founder("Bo", 1, date(1), bd("40.0"), null, null, 8, 1)), AT);

        assertThat(withTasks.tasksAssigned()).isEqualTo(16);
        assertThat(withTasks.tasksCompleted()).isEqualTo(7);
        assertThat(withTasks.completionRate()).isEqualByComparingTo("43.8");
        assertThat(withTasks.founders().getFirst().completionRate()).isEqualByComparingTo("75.0");

        RoiReportResponse noTasks = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 0, null, null, null, null, 0, 0)), AT);
        assertThat(noTasks.completionRate()).isNull();
        assertThat(noTasks.founders().getFirst().completionRate()).isNull();
    }

    @Test
    void measurementWindowSpansEarliestIntakeToLatestAssessment() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 2, date(10), bd("40.0"), date(40), bd("55.0"), 0, 0),
                        // Measured once: their intake IS their latest and still
                        // closes the window if it is the most recent point.
                        founder("Bo", 1, date(5), bd("30.0"), null, null, 0, 0),
                        founder("Cy", 1, date(50), bd("30.0"), null, null, 0, 0)), AT);

        assertThat(report.periodStart()).isEqualTo(date(5));
        assertThat(report.periodEnd()).isEqualTo(date(50));
    }

    @Test
    void unmeasuredProgramHasNoWindowAndNoNumbers() {
        RoiReportResponse report = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 0, null, null, null, null, 0, 0)), AT);

        assertThat(report.periodStart()).isNull();
        assertThat(report.periodEnd()).isNull();
        assertThat(report.foundersMeasured()).isZero();
        assertThat(report.pillars()).allSatisfy(p -> assertThat(p.delta()).isNull());
        assertThat(report.generatedAt()).isEqualTo(AT);
        assertThat(report.organizationName()).isEqualTo("Acme Accelerator");
        assertThat(report.programName()).isEqualTo("Winter 2026");
        assertThat(report.assessmentName()).isEqualTo("Founder Readiness");
    }

    @Test
    void periodCopyNamesTheWindow_orSaysThereIsNone() {
        RoiReportResponse measured = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 2, LocalDate.of(2026, 1, 12), bd("40.0"),
                        LocalDate.of(2026, 6, 4), bd("55.0"), 0, 0)), AT);
        assertThat(RoiReportPdfService.period(measured)).isEqualTo("12 Jan 2026 – 4 Jun 2026");

        RoiReportResponse sameDay = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 1, LocalDate.of(2026, 1, 12), bd("40.0"), null, null, 0, 0)), AT);
        assertThat(RoiReportPdfService.period(sameDay)).isEqualTo("12 Jan 2026");

        RoiReportResponse none = RoiReportService.assemble(HEADER, PILLARS, List.of(),
                List.of(founder("Ada", 0, null, null, null, null, 0, 0)), AT);
        assertThat(RoiReportPdfService.period(none)).isEqualTo("No assessments recorded yet");
    }

    private static FounderRow founder(String name, int assessments, LocalDate intakeOn,
                                      BigDecimal intakeScore, LocalDate latestOn,
                                      BigDecimal latestScore, int assigned, int done) {
        return new FounderRow(name, assessments, intakeOn, intakeScore, latestOn, latestScore,
                assigned, done);
    }

    private static LocalDate date(int dayOfYear) {
        return LocalDate.ofYearDay(2026, dayOfYear);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
