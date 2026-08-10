package com.bvisionry.organization;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.organization.dto.ActivityFeedResponse;
import com.bvisionry.organization.dto.BrandingResponse;
import com.bvisionry.organization.dto.ChangeTierRequest;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.ExtendTrialRequest;
import com.bvisionry.organization.dto.NudgeSettingsDto;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.StartTrialRequest;
import com.bvisionry.organization.dto.UpdateBrandingRequest;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
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
    private final SubOrganizationService subOrganizationService;
    private final TrialService trialService;
    private final ActivityService activityService;
    private final OrganizationBrandingService brandingService;
    /**
     * Reads the notification retention window that bounds the nudge window
     * below. Through {@link Environment} rather than {@code @Value} because
     * this class is {@code @RequiredArgsConstructor} and there is no
     * {@code lombok.config} making {@code @Value} copyable onto the generated
     * constructor — a {@code @Value} field here would silently bind nothing.
     */
    private final Environment environment;

    /** Owner of the property; the DB CHECK in V149 mirrors the 90 default. */
    private static final String RETENTION_DAYS = "bvisionry.notifications.retention-days";

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        // Root orgs are created together with their default "General" sub-org —
        // members live in sub-orgs only.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subOrganizationService.createRootOrganization(request, actorId));
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

    // Name + description are the org's OWN profile, so this carries the same
    // in-org override as GET /{id} and /{id}/nudge-settings rather than the
    // class-level SUPER_ADMIN-only guard (redesign F-11 ruling, 2026-08-10: the
    // sub-org Settings tab opens to that sub-org's admins, split by decision
    // ownership). This request body carries name/description and NOTHING else —
    // tier, trial, active state and deletion keep their own SUPER_ADMIN-only
    // verbs above/below, which is why opening this one is not a widening of
    // those. The write is audited (ORGANIZATION_UPDATED) either way.
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
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

    // Inactivity nudges (roadmap §7 items 7 + 18). The window is the org's own
    // knob, so it uses the same in-org override as GET /{id} and /{id}/activity
    // rather than the class-level SUPER_ADMIN-only guard: an ORG_ADMIN tunes
    // their own org (and, via @orgAccess hierarchy, the sub-orgs they govern),
    // and nobody else's. Layer 1 is the HTTP filter chain (authenticated),
    // layer 2 is this @PreAuthorize pinning role AND org, layer 3 is the
    // service resolving the org by id and 404ing when it does not exist.
    @GetMapping("/{id}/nudge-settings")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
    public ResponseEntity<NudgeSettingsDto> getNudgeSettings(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getNudgeSettings(id));
    }

    @PutMapping("/{id}/nudge-settings")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
    public ResponseEntity<NudgeSettingsDto> updateNudgeSettings(@PathVariable UUID id,
                                                                @Valid @RequestBody NudgeSettingsDto request) {
        // The BINDING cap is derived here, not the DTO's static @Max(90).
        // Send-once is decided by reading the notification history, which
        // NotificationRetentionJob purges at RETENTION_DAYS — a window longer
        // than retention reads as "never nudged" the moment the evidence is
        // purged, and re-nudges early. Deriving it means an operator who
        // tightens retention tightens this with it, instead of the two drifting
        // apart behind a hardcoded 90. Non-positive retention disables the
        // purge, so history is kept forever and any window is safe.
        //
        // Here rather than in OrganizationService because that class's
        // constructor carries five cross-feature parameters frozen by
        // ArchitectureRulesTest rule 1 BY SIGNATURE: adding one parameter
        // re-flags all five as new violations (observed, not assumed), and the
        // frozen store is never-write. This constructor has no such parameters.
        // Request validation is the controller's layer anyway — it is the same
        // concern as the @Valid above, only dynamic.
        int retentionDays = environment.getProperty(RETENTION_DAYS, Integer.class, 90);
        if (retentionDays > 0 && request.inactivityNudgeDays() > retentionDays) {
            throw new BadRequestException("Inactivity nudge window cannot exceed the "
                    + retentionDays + "-day notification retention window");
        }
        return ResponseEntity.ok(organizationService.updateNudgeSettings(id, request));
    }

    // ----------------------------------------------------------------- branding
    // White-label logo + brand colour (policy decisions.white_label). Two
    // DELIBERATELY DIFFERENT gates:
    //
    // WRITE mirrors nudge-settings exactly — SUPER_ADMIN, or an ORG_ADMIN of
    // this org (or of its parent, via @orgAccess's one-level hierarchy). The
    // wholesale PUT /{id} stays SUPER_ADMIN-only; branding gets its own verb so
    // an org admin can set a logo without also gaining name/description writes.
    //
    // READ is wider ON PURPOSE and this is the one deviation worth naming: the
    // branded surface is EVERY signed-in page, so the app shell fetches this for
    // every viewer — MEMBER, COACH, INSTRUCTOR included. Gated like the write it
    // would 403 for everyone except admins and no member would ever see their
    // own org's brand, which is the entire acceptance criterion. @orgAccess.isInOrg
    // alone is still the correct tenancy predicate (it is true only for members of
    // this org, its parent's admins, or a SUPER_ADMIN), and what it exposes is a
    // logo and a colour that the same viewer sees rendered on every page anyway.
    @GetMapping("/{id}/branding")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or @orgAccess.isInOrg(#id)")
    public ResponseEntity<BrandingResponse> getBranding(@PathVariable UUID id) {
        return ResponseEntity.ok(brandingService.get(id));
    }

    @PutMapping("/{id}/branding")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#id))")
    public ResponseEntity<BrandingResponse> updateBranding(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdateBrandingRequest request) {
        // The actor is resolved INSIDE the service, via the shared-kernel
        // CurrentUserAccessor. SecurityUtils lives in the auth feature and the
        // ArchUnit ratchet freezes cross-feature calls per CALL SITE, so a
        // getCurrentUserId() here would be a brand-new violation — and the
        // frozen store is never written.
        return ResponseEntity.ok(brandingService.update(id, request));
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
