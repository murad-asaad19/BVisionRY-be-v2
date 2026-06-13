package com.bvisionry.organization;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.organization.dto.AcceptInvitationRequest;
import com.bvisionry.organization.dto.InvitationAttemptResponse;
import com.bvisionry.organization.dto.InvitationResponse;
import com.bvisionry.organization.dto.InviteMembersRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class InvitationController {

    private final InvitationService invitationService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/api/organizations/{orgId}/members/invite")
    public ResponseEntity<List<InvitationResponse>> invite(@PathVariable UUID orgId,
                                                            @Valid @RequestBody InviteMembersRequest request) {
        // Override client-supplied invitedBy with the authenticated user
        InviteMembersRequest securedRequest = new InviteMembersRequest(
                request.emails(), request.role(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.inviteMembers(orgId, securedRequest));
    }

    @GetMapping("/api/invitations/{token}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<InvitationResponse> getInvitation(@PathVariable UUID token) {
        return ResponseEntity.ok(invitationService.getInvitationByToken(token));
    }

    @PostMapping("/api/invitations/{token}/accept")
    @PreAuthorize("permitAll()")
    public ResponseEntity<AuthResponse> accept(
            @PathVariable UUID token,
            @RequestBody(required = false) AcceptInvitationRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkAcceptLimit(clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(invitationService.acceptInvitationWithRegistration(token, request));
    }

    @GetMapping("/api/organizations/{orgId}/invitations")
    public ResponseEntity<List<InvitationResponse>> listByOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(invitationService.listByOrganization(orgId));
    }

    @GetMapping("/api/organizations/{orgId}/invitations/{invitationId}/attempts")
    public ResponseEntity<List<InvitationAttemptResponse>> listAttempts(
            @PathVariable UUID orgId, @PathVariable UUID invitationId) {
        return ResponseEntity.ok(invitationService.listAttempts(orgId, invitationId));
    }

    @DeleteMapping("/api/organizations/{orgId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        invitationService.revokeInvitation(orgId, invitationId);
        return ResponseEntity.noContent().build();
    }
}
