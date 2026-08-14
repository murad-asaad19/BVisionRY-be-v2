package com.bvisionry.engagement.web;

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
import com.bvisionry.common.security.ExportNameGuard;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The cohort Engagement tab as a download — the participation matrix (spec §4)
 * as a branded PDF or an Excel workbook. Same gate as the on-screen read
 * ({@link CohortParticipationController}): the org's own admin or a super
 * admin; a cohort the org is not assigned to is a 404.
 *
 * <p>{@code showNames=true} is SUPER_ADMIN / ORG_ADMIN-only
 * ({@link ExportNameGuard}); masked members render as positional
 * "Member 1..N" labels, the multi-member-document convention the insight and
 * workshop exports use.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/cohorts/{cohortId}")
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Engagement (admin)", description = "Participation score + attendance history for one founder.")
public class EngagementReportController {

    private final EngagementReportExportService exports;

    @GetMapping("/engagement-report.pdf")
    public ResponseEntity<byte[]> pdf(
            @PathVariable UUID orgId, @PathVariable UUID cohortId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        ExportNameGuard.checkShowNames(showNames);
        byte[] pdf = exports.pdf(orgId, cohortId, showNames);
        String filename = filename(orgId, cohortId) + ".pdf";
        String disposition = "preview".equalsIgnoreCase(mode) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    /** Same matrix as the PDF, one sheet, numbers as numbers. */
    @GetMapping("/engagement-report.xlsx")
    public ResponseEntity<byte[]> xlsx(
            @PathVariable UUID orgId, @PathVariable UUID cohortId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        ExportNameGuard.checkShowNames(showNames);
        byte[] xlsx = exports.excel(orgId, cohortId, showNames);
        return XlsxResponse.build(xlsx, filename(orgId, cohortId) + ".xlsx", mode);
    }

    /**
     * Named after the cohort, resolved from the database — never from a
     * caller-supplied parameter, so the download cannot be made to claim
     * another cohort's name.
     */
    private String filename(UUID orgId, UUID cohortId) {
        String cohort = XlsxResponse.sanitizeFilename(exports.cohortName(orgId, cohortId));
        return "Engagement_Report" + (cohort.isEmpty() ? "" : "_" + cohort);
    }
}
