package com.bvisionry.comparison.web;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.security.PremiumFeatureGuard;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The cohort Growth tab's key stats as a download. Excel only (operator
 * decision: reuse the existing exporter, no CSV) and aggregates only — no
 * member rows, so unlike the engagement report there is no showNames switch.
 * Org-scoped like every other cohort read, and premium-gated as
 * {@code pdf_report} like the rest of the file exports.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/cohorts/{cohortId}")
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (admin)",
        description = "Cohort comparison reads, mapping view + self-service recompute.")
public class CohortGrowthReportController {

    private final CohortGrowthReportExportService exports;
    private final PremiumFeatureGuard premiumFeatureGuard;

    @GetMapping("/growth-report.xlsx")
    public org.springframework.http.ResponseEntity<byte[]> xlsx(
            @PathVariable UUID orgId, @PathVariable UUID cohortId,
            @RequestParam(defaultValue = "download") String mode) {
        premiumFeatureGuard.checkPremium(orgId, "pdf_report");
        byte[] xlsx = exports.excel(orgId, cohortId);
        String cohort = XlsxResponse.sanitizeFilename(exports.cohortName(orgId, cohortId));
        String filename = "Cohort_Growth_Report" + (cohort.isEmpty() ? "" : "_" + cohort) + ".xlsx";
        return XlsxResponse.build(xlsx, filename, mode);
    }
}
