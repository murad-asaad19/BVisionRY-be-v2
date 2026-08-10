package com.bvisionry.engagement.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.engagement.dto.EngagementRecordResponse.CohortParticipationResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The cohort board Pulse tab's participation column (spec §4 names Pulse as a
 * participation surface alongside the matrix and the founder-profile header).
 * One request for the whole roster instead of one per founder; same guard
 * stack as the Sessions tab that enters the attendance behind it.
 *
 * <p>Admin-only, like every other participation surface except the coach's
 * per-founder profile read — there is no member-facing door (§4 DECIDED).
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/cohorts/{cohortId}/participation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Engagement (admin)", description = "Participation score + attendance history for one founder.")
public class CohortParticipationController {

    private final EngagementService service;

    @GetMapping
    public CohortParticipationResponse cohortParticipation(@PathVariable UUID orgId,
            @PathVariable UUID cohortId) {
        return service.cohortParticipation(orgId, cohortId);
    }
}
