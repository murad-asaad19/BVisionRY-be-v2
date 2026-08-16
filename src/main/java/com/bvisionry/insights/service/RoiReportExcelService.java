package com.bvisionry.insights.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.springframework.stereotype.Service;

import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.insights.dto.RoiFounderDeltaDto;
import com.bvisionry.insights.dto.RoiPillarMovementDto;
import com.bvisionry.insights.dto.RoiReportResponse;

/**
 * Renders the cohort ROI report as a workbook, on the same
 * {@link ExcelWorkbookBuilder} the org-insights and workshop exports use — same
 * header styling, same auto-sizing, no new dependency.
 *
 * <p>Scores and rates are written as NUMBERS, not formatted strings, so a funder
 * can sort, filter and chart them. A measurement that does not exist is written
 * as a blank cell (the builder's null handling) — never a zero, which would read
 * as "measured at zero".
 */
@Service
public class RoiReportExcelService {

    public byte[] render(RoiReportResponse report) {
        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            writeOverview(wb, report);
            writePillarMovement(wb, report);
            writeFounderDeltas(wb, report);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate the program outcomes workbook", e);
        }
    }

    private void writeOverview(ExcelWorkbookBuilder wb, RoiReportResponse r) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Overview");
        s.headers("Field", "Value");
        s.labeledRow("Organization", r.organizationName());
        s.labeledRow("Program", r.programName());
        s.labeledRow("Assessment", r.assessmentName());
        s.labeledRow("Measurement period", RoiReportPdfService.period(r));
        s.labeledRow("Founders in the program", r.cohortSize());
        s.labeledRow("Assessed at least once", r.foundersMeasured());
        s.labeledRow("Assessed twice or more", r.foundersRemeasured());
        s.labeledRow("Module steps assigned", r.tasksAssigned());
        s.labeledRow("Module steps submitted", r.tasksCompleted());
        s.labeledRow("Module completion %", r.completionRate());
        s.labeledRow("Generated", r.generatedAt());
        s.autoSize();
    }

    private void writePillarMovement(ExcelWorkbookBuilder wb, RoiReportResponse r) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Pillar movement");
        s.headers("Pillar", "Founders compared", "Intake average", "Latest average",
                "Change", "Direction");
        for (RoiPillarMovementDto p : r.pillars()) {
            s.row(p.pillarName(), p.foundersPaired(), p.intakeAverage(), p.latestAverage(),
                    p.delta(), p.direction());
        }
        s.autoSize();
    }

    private void writeFounderDeltas(ExcelWorkbookBuilder wb, RoiReportResponse r) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Founder deltas");
        s.headers("Founder", "Assessments", "Intake date", "Intake score",
                "Latest date", "Latest score", "Change", "Direction",
                "Module steps assigned", "Module steps submitted", "Module completion %");
        for (RoiFounderDeltaDto f : r.founders()) {
            s.row(f.founderName(), f.assessments(), f.intakeOn(), f.intakeScore(),
                    f.latestOn(), f.latestScore(), f.delta(), f.direction(),
                    f.tasksAssigned(), f.tasksCompleted(), f.completionRate());
        }
        s.autoSize();
    }
}
