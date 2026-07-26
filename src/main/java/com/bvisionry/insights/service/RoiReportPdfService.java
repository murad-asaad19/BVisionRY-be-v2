package com.bvisionry.insights.service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.insights.dto.RoiReportResponse;

import lombok.RequiredArgsConstructor;

/**
 * Renders the cohort ROI report as a funder-presentable PDF.
 *
 * <p>Reuses the shared {@link PdfRenderer} pipeline the org- and team-insights
 * exports already run on: the same Thymeleaf → Flying Saucer stack, the same
 * brand typeface and imagery (injected by the renderer), and the same
 * {@code fragments/pdf-base} design system — so this report looks like every
 * other document the platform hands out, with no new dependency.
 *
 * <p>The whole {@link RoiReportResponse} is handed to the template as-is: it is
 * already the id-free, funder-facing model, so there is nothing to reshape and
 * no second definition of a delta to keep in step.
 */
@Service
@RequiredArgsConstructor
public class RoiReportPdfService {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final PdfRenderer pdfRenderer;

    // ponytail: renders synchronously on the request thread, like every other
    // export here — but this is the first PDF whose page count scales with
    // customer input (one founder table row per cohort member), so a 500-founder
    // cohort is the ceiling. Move to the async job path the AI insight reports
    // already use if a render ever approaches the request timeout.
    public byte[] render(RoiReportResponse report) {
        Context ctx = new Context();
        ctx.setVariable("report", report);
        ctx.setVariable("period", period(report));
        return pdfRenderer.renderTemplate("roi-report", ctx);
    }

    /**
     * The measurement window in plain English. An unmeasured program says so
     * rather than printing an empty range.
     */
    static String period(RoiReportResponse report) {
        if (report.periodStart() == null || report.periodEnd() == null) {
            return "No assessments recorded yet";
        }
        String start = DAY.format(report.periodStart());
        String end = DAY.format(report.periodEnd());
        return start.equals(end) ? start : start + " – " + end;
    }
}
