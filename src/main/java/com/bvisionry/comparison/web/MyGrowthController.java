package com.bvisionry.comparison.web;

import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.exception.PremiumRequiredException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.security.PremiumFeatureGuard;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own growth-comparison read (spec §5). Identity-scoped: the
 * authenticated caller IS the founder — no id in the path to widen. The PDF +
 * Excel exports (spec §11) render the same lifecycle-honest payload; premium
 * gating mirrors the member results exports ("pdf_report").
 */
@RestController
@RequestMapping(path = "/api/my/growth")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My Growth", description = "The member's distance comparison and trajectory.")
public class MyGrowthController {

    private final ComparisonQueryService queries;
    private final MyGrowthExportService exports;
    private final PremiumFeatureGuard premiumFeatureGuard;
    private final CurrentUserAccessor currentUser;

    @GetMapping(path = "/comparison", produces = MediaType.APPLICATION_JSON_VALUE)
    public MyComparisonResponse comparison() {
        return queries.myComparison(currentUser.require().userId());
    }

    @GetMapping(path = "/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportPdf(
            @RequestParam(defaultValue = "download") String mode) {
        CurrentUser me = requirePremium();
        byte[] pdf = exports.pdf(me.userId());
        String filename = "My_Growth_Report.pdf";
        String disposition = "preview".equals(mode)
                ? "inline; filename=\"" + filename + "\""
                : "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping(path = "/report/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> reportExcel(
            @RequestParam(defaultValue = "download") String mode) {
        CurrentUser me = requirePremium();
        return XlsxResponse.build(exports.excel(me.userId()), "My_Growth_Report.xlsx", mode);
    }

    /**
     * Same premium key the member results exports use. An org-less caller is
     * DENIED (403), not waved through — no org means no plan to entitle them.
     * Super admins keep checkPremium's usual bypass.
     */
    private CurrentUser requirePremium() {
        CurrentUser me = currentUser.require();
        if (me.orgId() == null) {
            if (!UserRole.SUPER_ADMIN.name().equals(me.role())) {
                throw new PremiumRequiredException("pdf_report");
            }
            return me;
        }
        premiumFeatureGuard.checkPremium(me.orgId(), "pdf_report");
        return me;
    }
}
