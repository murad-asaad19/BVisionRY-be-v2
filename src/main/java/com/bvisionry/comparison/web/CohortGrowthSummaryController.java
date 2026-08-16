package com.bvisionry.comparison.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.security.PremiumFeatureGuard;
import com.bvisionry.comparison.dto.CohortGrowthSummaryDto;
import com.bvisionry.comparison.dto.NarrativeRequests.GenerateCohortSummaryRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The cohort-level growth report (spec §4) — same guard stack as
 * {@link ComparisonAdminController} (route-pattern org interceptor + the class
 * {@code @PreAuthorize}, and every service call carries {@code orgId}), plus the
 * premium gate org insights already sits behind: this is the same kind of
 * AI spend on the same kind of staff-facing report.
 *
 * <p>No {@code ExportNameGuard} on the real-names toggle, deliberately: §4 puts
 * that call in ANY org admin's hands (operator decision), unlike the
 * SUPER_ADMIN-only insight exports.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Cohort growth summary",
        description = "AI cohort-level growth report — generate, poll, history, retry.")
public class CohortGrowthSummaryController {

    private final CohortGrowthSummaryService summaries;
    private final PremiumFeatureGuard premiumFeatureGuard;
    private final CurrentUserAccessor currentUser;

    /** The poll target: newest row whatever its status. 204 before there has ever been one. */
    @GetMapping("/latest")
    public ResponseEntity<CohortGrowthSummaryDto> latest(@PathVariable UUID orgId,
                                                         @PathVariable UUID cohortId) {
        premiumFeatureGuard.checkPremium(orgId, "cohort_growth_summary");
        return summaries.latest(orgId, cohortId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public List<CohortGrowthSummaryDto> history(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        premiumFeatureGuard.checkPremium(orgId, "cohort_growth_summary");
        return summaries.history(orgId, cohortId);
    }

    @PostMapping("/generate")
    public ResponseEntity<CohortGrowthSummaryDto> generate(
            @PathVariable UUID orgId,
            @PathVariable UUID cohortId,
            @Valid @RequestBody GenerateCohortSummaryRequest request) {
        premiumFeatureGuard.checkPremium(orgId, "cohort_growth_summary");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(summaries.generate(
                orgId, cohortId, request.includeNames(), currentUser.require().userId()));
    }

    @PostMapping("/{summaryId}/retry")
    public ResponseEntity<CohortGrowthSummaryDto> retry(@PathVariable UUID orgId,
                                                        @PathVariable UUID cohortId,
                                                        @PathVariable UUID summaryId) {
        premiumFeatureGuard.checkPremium(orgId, "cohort_growth_summary");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(summaries.retry(orgId, summaryId, currentUser.require().userId()));
    }
}
