package com.bvisionry.organization;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.organization.dto.AcceptJoinLinkRequest;
import com.bvisionry.organization.dto.GenerateJoinLinkRequest;
import com.bvisionry.organization.dto.JoinLinkInfoResponse;
import com.bvisionry.organization.dto.JoinLinkResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class JoinLinkController {

    private final JoinLinkService joinLinkService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/api/organizations/{orgId}/join-link")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public ResponseEntity<JoinLinkResponse> generate(
            @PathVariable UUID orgId,
            @Valid @RequestBody GenerateJoinLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(joinLinkService.generate(orgId, request.expiryDays(), request.workshopId(),
                        SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/api/organizations/{orgId}/join-link")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public ResponseEntity<JoinLinkResponse> getActive(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID workshopId) {
        return joinLinkService.getActive(orgId, workshopId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/api/organizations/{orgId}/join-link")
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID workshopId) {
        joinLinkService.revoke(orgId, workshopId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/join/{token}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<JoinLinkInfoResponse> getJoinLinkInfo(@PathVariable UUID token) {
        return ResponseEntity.ok(joinLinkService.getJoinLinkInfo(token));
    }

    @PostMapping("/api/join/{token}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<com.bvisionry.auth.dto.AuthResponse> acceptJoinLink(
            @PathVariable UUID token,
            @Valid @RequestBody AcceptJoinLinkRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkAcceptLimit(clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(joinLinkService.acceptJoinLink(token, request));
    }
}
