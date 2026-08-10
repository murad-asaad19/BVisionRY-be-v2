package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.CohortPlanUsageResponse;
import com.bvisionry.programflow.dto.GrantLaunchRequest;
import com.bvisionry.programflow.dto.LaunchLedgerResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * SUPER_ADMIN half of spec §8 on the platform org page: quota usage (incl.
 * the §11 stalled-onboarding indicator), the §7b ledger, and the audit-logged
 * grant-extra-launch override.
 *
 * <ul>
 *   <li>GET  /api/admin/organizations/{orgId}/cohort-quota          — usage view</li>
 *   <li>GET  /api/admin/organizations/{orgId}/cohort-quota/ledger   — launch + grant history</li>
 *   <li>POST /api/admin/organizations/{orgId}/cohort-quota/grants   — +1 launch this period (note optional)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/admin/organizations/{orgId}/cohort-quota",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Cohort quota (platform)", description = "Usage view and grant-extra-launch override.")
public class CohortQuotaAdminController {

    private final LaunchQuotaService service;

    public CohortQuotaAdminController(LaunchQuotaService service) {
        this.service = service;
    }

    @GetMapping
    public CohortPlanUsageResponse usage(@PathVariable UUID orgId) {
        return service.usage(orgId);
    }

    @GetMapping("/ledger")
    public LaunchLedgerResponse ledger(@PathVariable UUID orgId) {
        return service.history(orgId);
    }

    @PostMapping("/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public CohortPlanUsageResponse grant(@PathVariable UUID orgId,
            @Valid @RequestBody GrantLaunchRequest req) {
        return service.grantExtraLaunch(orgId, req);
    }
}
