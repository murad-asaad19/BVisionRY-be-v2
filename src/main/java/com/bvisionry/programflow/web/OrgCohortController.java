package com.bvisionry.programflow.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The org participation surface (spec §13.8): the cohorts assigned to this
 * org, rosters cut to the org's OWN members, and the one write an org admin
 * keeps — managing that roster slice. Curriculum, lifecycle and org
 * assignment live on the platform controller.
 *
 * <ul>
 *   <li>GET /api/organizations/{orgId}/cohorts                    — assigned cohorts (own members only)</li>
 *   <li>PUT /api/organizations/{orgId}/cohorts/{cohortId}/members — replace the org's roster slice</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Cohorts (org)", description = "Org participation: assigned cohorts and own-roster management.")
public class OrgCohortController {

    private final CohortService service;

    public OrgCohortController(CohortService service) {
        this.service = service;
    }

    @GetMapping
    public List<CohortDto> list(@PathVariable UUID orgId) {
        return service.listAssigned(orgId);
    }

    @PutMapping("/{cohortId}/members")
    public CohortDto setMembers(
            @PathVariable UUID orgId,
            @PathVariable UUID cohortId,
            @Valid @RequestBody UpdateCohortMembersRequest req) {
        return service.setOrgMembers(orgId, cohortId, req);
    }
}
