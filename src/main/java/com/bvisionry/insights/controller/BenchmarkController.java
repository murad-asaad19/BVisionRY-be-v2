package com.bvisionry.insights.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.insights.dto.BenchmarkResponse;
import com.bvisionry.insights.service.BenchmarkService;
import com.bvisionry.common.security.PremiumFeatureGuard;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Quantitative benchmarking (roadmap §7 item 19): a cohort's pillar scores
 * against the org's own distribution and the anonymous platform-wide
 * distribution — a real cross-tenant measurement, not an AI narrative.
 *
 * <p>Three-layer defense, matching the sibling org-scoped insight surfaces:
 * the security chain requires authentication on every {@code /api/**} route,
 * this class re-asserts ORG_ADMIN-of-this-org / SUPER_ADMIN via
 * {@code @PreAuthorize} + {@code @orgAccess} (which pins the path org to the
 * caller's session org, one hierarchy level deep), and every query in
 * {@link com.bvisionry.insights.BenchmarkReadRepository} carries the tenant
 * predicate in the SQL itself. COACH is deliberately outside the gate —
 * {@code coach_never_sees: platform_analytics}.
 *
 * <p>A fourth, orthogonal layer sits on top: ENTITLEMENT. The three layers above
 * answer "may this caller read THIS org's data"; {@link PremiumFeatureGuard}
 * answers "is this org paying for this feature at all", and 403s a FREE org's own
 * admin. Benchmarking is a sold line item, so the check is the handler's first
 * statement — before any query runs.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/benchmarks")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Benchmarks", description = "Cohort vs org vs platform pillar-score distributions.")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final PremiumFeatureGuard premiumFeatureGuard;

    /**
     * Per-pillar comparison for a pipeline; {@code cohortId} (optional) adds
     * the cohort segment and 404s when the cohort is not this org's.
     */
    @GetMapping
    public BenchmarkResponse get(@PathVariable UUID orgId,
                                 @RequestParam UUID pipelineId,
                                 @RequestParam(required = false) UUID cohortId) {
        // ponytail: binary PREMIUM gate — sold from Growth up; split per-tier when
        // the Starter/Growth/FS ladder exists as data.
        premiumFeatureGuard.checkPremium(orgId, "benchmarks");
        return benchmarkService.benchmarks(orgId, pipelineId, cohortId);
    }
}
