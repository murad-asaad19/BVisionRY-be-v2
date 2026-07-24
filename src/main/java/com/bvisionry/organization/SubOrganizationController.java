package com.bvisionry.organization;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.organization.dto.CreateOrganizationRequest;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.dto.UpdateOrganizationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sub-organization management, scoped under the PARENT org's path so the
 * standard tenancy gate applies: SUPER_ADMIN, or an ORG_ADMIN of
 * {@code orgId} (the parent). Create/rename/delete of a child is a
 * parent-level action — a sub-org's own admins do not manage sibling
 * sub-orgs or the sub-org's existence.
 *
 * <p>Request/response shapes reuse the plain organization DTOs: a sub-org IS
 * an organization row, just parented. The caller is resolved through the
 * common {@link CurrentUserAccessor} port (not {@code auth.SecurityUtils}) so
 * this new class adds no cross-feature dependency edge to the ArchUnit ratchet.
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/sub-organizations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class SubOrganizationController {

    private final SubOrganizationService subOrganizationService;
    private final CurrentUserAccessor currentUserAccessor;

    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> list(@PathVariable UUID orgId) {
        return ResponseEntity.ok(subOrganizationService.listSubOrganizations(orgId));
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@PathVariable UUID orgId,
                                                       @Valid @RequestBody CreateOrganizationRequest request) {
        UUID actorId = currentUserAccessor.require().userId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subOrganizationService.createSubOrganization(orgId, request, actorId));
    }

    @PutMapping("/{subOrgId}")
    public ResponseEntity<OrganizationResponse> update(@PathVariable UUID orgId,
                                                       @PathVariable UUID subOrgId,
                                                       @Valid @RequestBody UpdateOrganizationRequest request) {
        UUID actorId = currentUserAccessor.require().userId();
        return ResponseEntity.ok(subOrganizationService.updateSubOrganization(orgId, subOrgId, request, actorId));
    }

    @DeleteMapping("/{subOrgId}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID subOrgId) {
        UUID actorId = currentUserAccessor.require().userId();
        subOrganizationService.deleteSubOrganization(orgId, subOrgId, actorId);
        return ResponseEntity.noContent().build();
    }
}
