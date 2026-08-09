package com.bvisionry.founderprofile.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.founderprofile.dto.FounderProfileResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The org admin's founder profile read (spec §2.4). Same guard stack as the
 * other org-scoped consoles: {@code OrgAccessInterceptor} on the route pattern
 * plus the class {@code @PreAuthorize}; the service re-anchors on the
 * org-scoped member row, so a foreign member id is a 404.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/members/{memberId}/founder-profile",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Founder profile (admin)", description = "The shared founder profile for an org admin.")
public class FounderProfileAdminController {

    private final FounderProfileService service;

    @GetMapping
    public FounderProfileResponse memberFounderProfile(@PathVariable UUID orgId,
            @PathVariable UUID memberId) {
        return service.profile(orgId, memberId);
    }
}
