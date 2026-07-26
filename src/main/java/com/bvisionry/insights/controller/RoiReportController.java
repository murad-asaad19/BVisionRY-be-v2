package com.bvisionry.insights.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.insights.dto.RoiReportResponse;
import com.bvisionry.insights.service.RoiReportExcelService;
import com.bvisionry.insights.service.RoiReportPdfService;
import com.bvisionry.insights.service.RoiReportService;
import com.bvisionry.common.security.PremiumFeatureGuard;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Program outcomes / ROI reporting (roadmap §7 item 16): a cohort's pillar
 * movement from intake to latest with per-founder deltas, on screen and as a
 * PDF or workbook a program operator can hand to a funder.
 *
 * <p>Three-layer defense, matching the sibling {@link BenchmarkController}: the
 * security chain requires authentication on every {@code /api/**} route, this
 * class re-asserts ORG_ADMIN-of-this-org / SUPER_ADMIN via {@code @PreAuthorize}
 * + {@code @orgAccess} (which pins the path org to the caller's session org),
 * and every query in {@code RoiReportReadRepository} carries the org + cohort
 * predicate in the SQL itself. COACH, INSTRUCTOR and MEMBER are outside the gate
 * — this is a whole-cohort report naming every founder, which is the program
 * operator's view, not a participant's or a coach's caseload.
 *
 * <p>The three routes share one guard and one assembly: the JSON is exactly what
 * the exports render, so a funder can never receive a document the operator did
 * not see on screen.
 *
 * <p>A fourth, orthogonal layer sits on top: ENTITLEMENT. The three layers above
 * answer "may this caller read THIS org's data"; {@link PremiumFeatureGuard}
 * answers "is this org paying for this feature at all", and 403s a FREE org's own
 * admin. All THREE routes carry it identically — an export is not a lesser copy
 * of the on-screen report, it is the same product in a file, so gating only the
 * downloads would monetize nothing (print-to-PDF defeats it).
 */
@RestController
@RequestMapping("/api/organizations/{orgId}")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "ROI reporting",
        description = "Cohort pillar movement, per-founder deltas and completion, exportable for a funder.")
public class RoiReportController {

    private final RoiReportService roiReportService;
    private final RoiReportPdfService pdfService;
    private final RoiReportExcelService excelService;
    private final PremiumFeatureGuard premiumFeatureGuard;

    /**
     * The report model. A cohort that is not this org's, or a pipeline that is
     * absent or unpublished, reads as absent (404).
     */
    @GetMapping("/roi-report")
    public RoiReportResponse get(@PathVariable UUID orgId,
                                 @RequestParam UUID cohortId,
                                 @RequestParam UUID pipelineId) {
        // ponytail: binary PREMIUM gate — sold as Founder Success; split per-tier when the Starter/Growth/FS ladder exists as data.
        premiumFeatureGuard.checkPremium(orgId, "roi_report");
        return roiReportService.report(orgId, cohortId, pipelineId);
    }

    @GetMapping("/roi-report.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID orgId,
                                      @RequestParam UUID cohortId,
                                      @RequestParam UUID pipelineId,
                                      @RequestParam(defaultValue = "download") String mode) {
        premiumFeatureGuard.checkPremium(orgId, "roi_report");
        RoiReportResponse report = roiReportService.report(orgId, cohortId, pipelineId);
        byte[] body = pdfService.render(report);
        String disposition = "preview".equalsIgnoreCase(mode) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"" + filename(report) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping("/roi-report.xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID orgId,
                                       @RequestParam UUID cohortId,
                                       @RequestParam UUID pipelineId,
                                       @RequestParam(defaultValue = "download") String mode) {
        premiumFeatureGuard.checkPremium(orgId, "roi_report");
        RoiReportResponse report = roiReportService.report(orgId, cohortId, pipelineId);
        return XlsxResponse.build(excelService.render(report), filename(report) + ".xlsx", mode);
    }

    /**
     * Names the file after the program itself, resolved from the database —
     * never from a caller-supplied query parameter, so the download cannot be
     * made to claim another organization's name.
     */
    private static String filename(RoiReportResponse report) {
        String program = XlsxResponse.sanitizeFilename(report.programName());
        return "Program_Outcomes" + (program.isEmpty() ? "" : "_" + program);
    }
}
