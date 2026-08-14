package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.orgmember.OrgMemberAccess;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.dto.JourneyResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Read-only journey of a member for the shared founder profile (spec §2.4):
 * the EXACT payload the member's own Journey renders, so the admin/coach view
 * can never drift from what the founder sees. Coach access follows the Phase A
 * comparison precedent — once {@link CoachAccess} passes, the coach sees the
 * same full journey the founder sees.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Founder journey (admin/coach)", description = "Read-only member journey for the founder profile.")
public class MemberJourneyController {

    private final MyProgramService service;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;
    private final OrgMemberAccess orgMembers;

    @GetMapping(path = "/api/v1/coach/founders/{founderId}/journey",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('COACH')")
    public JourneyResponse coachFounderJourney(@PathVariable UUID founderId,
            @RequestParam(required = false) UUID cohortId) {
        CurrentUser coach = currentUser.require();
        if (!coachAccess.coachSees(coach.orgId(), coach.userId(), founderId)) {
            throw new ResourceNotFoundException("Founder", founderId.toString());
        }
        return service.journeyOfMember(coach.orgId(), founderId, cohortId);
    }

    @GetMapping(path = "/api/organizations/{orgId}/members/{memberId}/journey",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public JourneyResponse memberJourney(@PathVariable UUID orgId, @PathVariable UUID memberId,
            @RequestParam(required = false) UUID cohortId) {
        // `journeyOfMember` TREATS orgId as "the viewed member's org" and scopes
        // their direct assignments and course visibility with it — a premise it
        // never checks. Since V171 a cohort spans orgs, so prove the premise
        // here or an org-A admin reads org B's founder through org A's URL.
        orgMembers.requireMemberOf(orgId, memberId);
        return service.journeyOfMember(orgId, memberId, cohortId);
    }
}
