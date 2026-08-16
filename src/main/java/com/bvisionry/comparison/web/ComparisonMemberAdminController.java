package com.bvisionry.comparison.web;

import java.util.UUID;

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
import com.bvisionry.common.security.PremiumFeatureGuard;
import com.bvisionry.comparison.dto.MyComparisonResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Org-admin, member-anchored comparison read + exports for the founder
 * profile's Growth tab: the SAME lifecycle payload ({@code done|pending|none}
 * + trajectory) and the SAME PDF/Excel renderer the member gets (spec §11 —
 * "the founder profile Growth tab gets the same PDF/Excel exports"), so the
 * three surfaces can never disagree on the report state. Same guard stack as
 * {@link ComparisonAdminController}.
 *
 * <p>Premium is checked against the FOUNDER's org (the plan that entitles the
 * report), not the caller's, which for this route are the same org — the
 * {@code OrgAccessInterceptor} already refused a foreign one.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/members/{userId}")
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (admin)", description = "Member-anchored comparison for the founder profile.")
public class ComparisonMemberAdminController {

    private final ComparisonQueryService queries;
    private final MyGrowthExportService exports;
    private final PremiumFeatureGuard premiumFeatureGuard;

    @GetMapping(path = "/growth-comparison", produces = MediaType.APPLICATION_JSON_VALUE)
    public MyComparisonResponse memberGrowthComparison(@PathVariable UUID orgId,
            @PathVariable UUID userId) {
        return queries.memberComparisonForAdmin(orgId, userId);
    }

    /**
     * {@code showNames} defaults to FALSE and true is SUPER_ADMIN-only
     * ({@link ExportNameGuard}) — this is the admin view of somebody else's
     * report; the member's own copy is {@code /api/my/growth/...}, where the
     * name is always their own. Masked, the founder appears as "Member" in the
     * document AND the filename.
     */
    @GetMapping(path = "/growth-report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> growthReportPdf(@PathVariable UUID orgId,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames,
            @RequestParam(required = false) String tz) {
        ExportNameGuard.checkShowNames(showNames);
        requireMemberAndPremium(orgId, userId);
        // Staff voice: an admin's copy speaks about the member, never AS them.
        return MyGrowthExportService.pdfResponse(
                exports.pdf(userId, showNames, true, MyGrowthExportService.zoneOrUtc(tz)),
                exports.reportFilename(userId, "pdf", showNames), mode);
    }

    /** Same masking default and same SUPER_ADMIN-only {@code showNames} as the PDF above. */
    @GetMapping(path = "/growth-report.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> growthReportExcel(@PathVariable UUID orgId,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames,
            @RequestParam(required = false) String tz) {
        ExportNameGuard.checkShowNames(showNames);
        requireMemberAndPremium(orgId, userId);
        return XlsxResponse.build(
                exports.excel(userId, showNames, MyGrowthExportService.zoneOrUtc(tz)),
                exports.reportFilename(userId, "xlsx", showNames), mode);
    }

    /** Member-in-org first (a foreign id is a 404, never a 402), then the plan. */
    private void requireMemberAndPremium(UUID orgId, UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        premiumFeatureGuard.checkPremium(orgId, "pdf_report");
    }
}
