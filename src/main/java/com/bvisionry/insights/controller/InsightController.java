package com.bvisionry.insights.controller;

import com.bvisionry.insights.dto.InsightGenerateRequest;
import com.bvisionry.insights.dto.InsightGenerateResponse;
import com.bvisionry.insights.dto.InsightListResponse;
import com.bvisionry.insights.dto.InsightReportResponse;
import com.bvisionry.insights.service.InsightService;
import com.bvisionry.reporting.service.PremiumFeatureGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/insights")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class InsightController {

    private final InsightService insightService;
    private final PremiumFeatureGuard premiumFeatureGuard;

    /**
     * Generate AI team insight report.
     * Premium only.
     */
    @PostMapping("/generate")
    public ResponseEntity<InsightGenerateResponse> generateInsight(
            @PathVariable UUID orgId,
            @Valid @RequestBody InsightGenerateRequest request) {
        premiumFeatureGuard.checkPremium(orgId, "team_insights");
        InsightGenerateResponse response = insightService.generateInsight(
                orgId, request.pipelineId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Get a specific insight report.
     */
    @GetMapping("/{reportId}")
    public ResponseEntity<InsightReportResponse> getReport(
            @PathVariable UUID orgId,
            @PathVariable UUID reportId) {
        return ResponseEntity.ok(insightService.getReport(orgId, reportId));
    }

    /**
     * List all insight reports for the organization.
     */
    @GetMapping
    public ResponseEntity<InsightListResponse> listReports(@PathVariable UUID orgId) {
        return ResponseEntity.ok(insightService.listReports(orgId));
    }

}
