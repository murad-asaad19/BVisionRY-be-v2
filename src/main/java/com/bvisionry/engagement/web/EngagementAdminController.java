package com.bvisionry.engagement.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.engagement.dto.EngagementRecordResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The org admin's engagement-record read (spec §4) — a sibling of the founder
 * profile endpoint (same guard stack); the profile Growth tab composes both.
 * Visibility (§4 DECIDED): admins and the assigned coach only. A member's own
 * record is reachable through {@link MyEngagementController}, never another
 * founder's.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/members/{memberId}/engagement",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Engagement (admin)", description = "Participation score + attendance history for one founder.")
public class EngagementAdminController {

    private final EngagementService service;

    @GetMapping
    public EngagementRecordResponse memberEngagement(@PathVariable UUID orgId,
            @PathVariable UUID memberId) {
        return service.record(orgId, memberId);
    }
}
