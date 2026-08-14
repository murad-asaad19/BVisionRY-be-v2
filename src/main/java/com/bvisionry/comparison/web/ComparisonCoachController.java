package com.bvisionry.comparison.web;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.security.ExportNameGuard;
import com.bvisionry.common.security.PremiumFeatureGuard;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The coach's read of a founder's growth comparison + the same PDF/Excel
 * exports the admin door and the member get (spec §11) — gated by the shared
 * {@link CoachAccess} assignment-union predicate (a founder outside the union
 * is a 404, exactly as in the coach console). Route floor
 * {@code /api/v1/coach/**} requires COACH in SecurityConfig; the class
 * re-asserts it.
 */
@RestController
@RequestMapping("/api/v1/coach/founders/{founderId}")
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (coach)", description = "A founder's comparison for their coach.")
public class ComparisonCoachController {

    private final ComparisonQueryService queries;
    private final MyGrowthExportService exports;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;
    private final PremiumFeatureGuard premiumFeatureGuard;

    @GetMapping(path = "/comparison", produces = MediaType.APPLICATION_JSON_VALUE)
    public MyComparisonResponse comparison(@PathVariable UUID founderId) {
        requireCoachSees(founderId);
        return queries.founderComparisonForCoach(founderId);
    }

    /**
     * The coach export is ALWAYS masked: no coach-scoped export anywhere
     * unmasks names — that privilege is SUPER_ADMIN-only, and the class-level
     * COACH gate means no super admin can even reach this door. The
     * {@code showNames} param exists so an explicit {@code true} is refused
     * loudly ({@link ExportNameGuard}, 403) rather than silently downgraded.
     */
    @GetMapping(path = "/growth-report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> growthReportPdf(@PathVariable UUID founderId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        ExportNameGuard.checkShowNames(showNames);
        requireSeesAndPremium(founderId);
        return MyGrowthExportService.pdfResponse(exports.pdf(founderId, false),
                exports.reportFilename(founderId, "pdf", false), mode);
    }

    /** Same always-masked rule as the PDF above. */
    @GetMapping(path = "/growth-report.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> growthReportExcel(@PathVariable UUID founderId,
            @RequestParam(defaultValue = "download") String mode,
            @RequestParam(defaultValue = "false") boolean showNames) {
        ExportNameGuard.checkShowNames(showNames);
        requireSeesAndPremium(founderId);
        return XlsxResponse.build(exports.excel(founderId, false),
                exports.reportFilename(founderId, "xlsx", false), mode);
    }

    private CurrentUser requireCoachSees(UUID founderId) {
        CurrentUser coach = currentUser.require();
        if (!coachAccess.coachSees(coach.orgId(), coach.userId(), founderId)) {
            throw new ResourceNotFoundException("Founder", founderId.toString());
        }
        return coach;
    }

    /** Assignment union first (an unassigned founder is a 404, never a 402), then the plan. */
    private void requireSeesAndPremium(UUID founderId) {
        premiumFeatureGuard.checkPremium(requireCoachSees(founderId).orgId(), "pdf_report");
    }
}
