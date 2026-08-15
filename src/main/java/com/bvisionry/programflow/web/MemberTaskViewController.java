package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.orgmember.OrgMemberAccess;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.dto.PlayerResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Read-only LESSON player of a member for the shared founder profile (spec
 * §2.4): the EXACT payload the member's own player renders, {@code readOnly}
 * forced on, so staff read the founder's answers with zero drift from what the
 * founder sees. Same guard split as {@link MemberJourneyController}. LESSON
 * only — assessment answers stay behind the §2.4 privacy invariant, and the
 * other types have their own review/results surfaces.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Founder task view (admin/coach)",
        description = "Read-only member lesson-task answers for the founder profile.")
public class MemberTaskViewController {

    private final MyProgramService service;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;
    private final OrgMemberAccess orgMembers;

    @GetMapping(path = "/api/v1/coach/founders/{founderId}/program-tasks/{taskId}/player",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('COACH')")
    public PlayerResponse coachFounderTaskPlayer(@PathVariable UUID founderId,
            @PathVariable UUID taskId) {
        CoachAccess.ViewedFounder coach = coachAccess.requireSees(founderId);
        return service.playerOfMember(founderId, taskId);
    }

    @GetMapping(path = "/api/organizations/{orgId}/members/{memberId}/program-tasks/{taskId}/player",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public PlayerResponse memberTaskPlayer(@PathVariable UUID orgId, @PathVariable UUID memberId,
            @PathVariable UUID taskId) {
        // The class guard proves the CALLER may act in this org; it says nothing
        // about the member named in the path. Since V171 a cohort spans orgs, so
        // without this a org-A admin could read org B's founder through org A's
        // own URL. The coach door needs no twin: CoachAccess already pins the
        // founder to the coach's org.
        orgMembers.requireMemberOf(orgId, memberId);
        return service.playerOfMember(memberId, taskId);
    }
}
