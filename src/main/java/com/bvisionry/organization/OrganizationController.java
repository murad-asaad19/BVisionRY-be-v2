package com.bvisionry.organization;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.organization.dto.ActivityFeedResponse;
import com.bvisionry.organization.dto.ChangeTierRequest;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.ExtendTrialRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.StartTrialRequest;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final TrialService trialService;
    private final ActivityService activityService;

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(organizationService.create(request, actorId));
    }

    @GetMapping
    public ResponseEntity<Page<OrganizationResponse>> listAll(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(organizationService.listAll(pageable));
    }

    // Same in-org override pattern as /{id}/activity below. @orgAccess.isInOrg
    // also grants a parent org's ORG_ADMIN access to its sub-orgs, so this lets
    // org admins load their own org profile AND any sub-org they govern.
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
    public ResponseEntity<OrganizationResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationResponse> update(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateOrganizationRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(organizationService.update(id, request, actorId));
    }

    @PatchMapping("/{id}/tier")
    public ResponseEntity<OrganizationResponse> changeTier(@PathVariable UUID id,
                                                            @Valid @RequestBody ChangeTierRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(organizationService.changeTier(id, request, actorId));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<OrganizationResponse> toggleActive(@PathVariable UUID id,
                                                              @RequestParam boolean active) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(organizationService.toggleActive(id, active, actorId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> hardDelete(@PathVariable UUID id) {
        organizationService.hardDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/trial")
    public ResponseEntity<OrganizationResponse> startTrial(@PathVariable UUID id,
                                                            @Valid @RequestBody(required = false) StartTrialRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        int days = request == null ? 7 : request.durationDaysOrDefault();
        return ResponseEntity.ok(trialService.startTrial(id, days, actorId));
    }

    @PatchMapping("/{id}/trial")
    public ResponseEntity<OrganizationResponse> extendTrial(@PathVariable UUID id,
                                                             @Valid @RequestBody ExtendTrialRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(trialService.extendTrial(id, request.additionalDays(), actorId));
    }

    @DeleteMapping("/{id}/trial")
    public ResponseEntity<OrganizationResponse> endTrialEarly(@PathVariable UUID id) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(trialService.endTrialEarly(id, actorId));
    }

    // The org-scoped activity feed is read by the org dashboard, which is reached
    // by in-org ORG_ADMINs (SUPER_ADMINs have a null org and never mount it).
    // Override the class-level SUPER_ADMIN-only guard to mirror the in-org pattern
    // used by TeamDashboardController/MemberController so ORG_ADMINs aren't 403'd.
    @GetMapping("/{id}/activity")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
    public ResponseEntity<ActivityFeedResponse> getActivity(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(activityService.getActivity(id, limit));
    }
}
