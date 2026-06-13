package com.bvisionry.insights.controller;

import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.insights.dto.InsightGenerateRequest;
import com.bvisionry.insights.dto.InsightGenerateResponse;
import com.bvisionry.insights.dto.InsightReportResponse;
import com.bvisionry.insights.service.InsightService;
import com.bvisionry.insights.service.OrgInsightExcelService;
import com.bvisionry.insights.service.OrgInsightPdfService;
import com.bvisionry.reporting.service.PremiumFeatureGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Organization-wide insights — aggregates all evaluated member data
 * for a selected pipeline and generates AI-powered team analysis.
 * Premium orgs + Super Admin only.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/org-insights")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class OrgInsightController {

    private final InsightService insightService;
    private final OrgInsightPdfService pdfService;
    private final OrgInsightExcelService excelService;
    private final PremiumFeatureGuard premiumFeatureGuard;

    /**
     * Get the latest org insight for a pipeline. Returns 204 if none exists.
     */
    @GetMapping("/latest")
    public ResponseEntity<InsightReportResponse> getLatest(
            @PathVariable UUID orgId,
            @RequestParam UUID pipelineId) {
        premiumFeatureGuard.checkPremium(orgId, "org_insights");
        return insightService.getLatestOrgInsight(orgId, pipelineId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Generate org-wide insight for a pipeline across all evaluated members.
     * Reuses the existing InsightService.generateInsight which aggregates
     * all pillar evaluations for the org+pipeline and calls AI.
     */
    @PostMapping("/generate")
    public ResponseEntity<InsightGenerateResponse> generate(
            @PathVariable UUID orgId,
            @Valid @RequestBody InsightGenerateRequest request) {
        premiumFeatureGuard.checkPremium(orgId, "org_insights");
        // pipelineId nullness is enforced by @NotNull + @Valid → 400 from the
        // global validation handler, so no need to duplicate the check here.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(insightService.generateInsight(orgId, request.pipelineId(), request.memberIds()));
    }

    /**
     * Download org insight report as PDF.
     */
    @GetMapping("/{reportId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID orgId,
            @PathVariable UUID reportId,
            @RequestParam(defaultValue = "") String orgName,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        premiumFeatureGuard.checkPremium(orgId, "org_insights");
        byte[] pdf = pdfService.generatePdf(orgId, reportId, orgName, showNames);
        String safeName = orgName.replaceAll("[^a-zA-Z0-9 _-]", "").replace(" ", "_");
        String filename = "Org_Insights_" + safeName + ".pdf";
        String disposition = "preview".equals(mode)
                ? "inline; filename=\"" + filename + "\""
                : "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/{reportId}/excel")
    public ResponseEntity<byte[]> downloadExcel(
            @PathVariable UUID orgId,
            @PathVariable UUID reportId,
            @RequestParam(defaultValue = "") String orgName,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        premiumFeatureGuard.checkPremium(orgId, "org_insights");
        byte[] xlsx = excelService.generate(orgId, reportId, orgName, showNames);
        String filename = "Org_Insights_" + XlsxResponse.sanitizeFilename(orgName) + ".xlsx";
        return XlsxResponse.build(xlsx, filename, mode);
    }
}
