package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.CohortPlanUsageResponse;
import com.bvisionry.programflow.dto.LaunchLedgerResponse;
import com.bvisionry.programflow.dto.OnboardingChecklistResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Org-admin reads for the cohorts surface (spec §8 / §11). All three answer
 * for the BILLING FAMILY whichever family org they're asked through.
 *
 * <ul>
 *   <li>GET /api/organizations/{orgId}/cohorts/plan-usage      — the plan-usage card</li>
 *   <li>GET /api/organizations/{orgId}/cohorts/launch-ledger   — §7b launch + grant history</li>
 *   <li>GET /api/organizations/{orgId}/onboarding-checklist    — §11 checklist booleans
 *       (dismissal is client-side; the backend keeps no dismiss state)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Cohort quota", description = "Launch quota usage, ledger and onboarding checklist.")
public class CohortQuotaController {

    private final LaunchQuotaService service;

    public CohortQuotaController(LaunchQuotaService service) {
        this.service = service;
    }

    @GetMapping("/cohorts/plan-usage")
    public CohortPlanUsageResponse planUsage(@PathVariable UUID orgId) {
        return service.usage(orgId);
    }

    @GetMapping("/cohorts/launch-ledger")
    public LaunchLedgerResponse launchLedger(@PathVariable UUID orgId) {
        return service.history(orgId);
    }

    @GetMapping("/onboarding-checklist")
    public OnboardingChecklistResponse onboardingChecklist(@PathVariable UUID orgId) {
        return service.checklist(orgId);
    }
}
