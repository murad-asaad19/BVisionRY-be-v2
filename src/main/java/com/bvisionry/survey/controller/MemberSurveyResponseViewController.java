package com.bvisionry.survey.controller;

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
import com.bvisionry.survey.dto.SurveyResponseDetailDto;
import com.bvisionry.survey.service.SurveyResultsService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Read-only view of a member's SURVEY journey-task response for the shared
 * founder profile (spec §2.4 — the Work tab's per-type view). Same guard split
 * as the programflow {@code MemberJourneyController}: the org guard stack for
 * admins (super admin included), {@link CoachAccess} for coaches. A survey
 * response is member-supplied program work, not assessment answer text, so the
 * §2.4 privacy invariant does not apply.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Founder survey response (admin/coach)",
        description = "Read-only member survey-task response for the founder profile.")
public class MemberSurveyResponseViewController {

    private final SurveyResultsService resultsService;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;
    private final OrgMemberAccess orgMembers;

    @GetMapping(path = "/api/v1/coach/founders/{founderId}/program-tasks/{taskId}/survey-response",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('COACH')")
    public SurveyResponseDetailDto coachFounderSurveyResponse(@PathVariable UUID founderId,
            @PathVariable UUID taskId) {
        CoachAccess.ViewedFounder coach = coachAccess.requireSees(founderId);
        return resultsService.responseDetailForProgramTask(taskId, founderId);
    }

    @GetMapping(path = "/api/organizations/{orgId}/members/{memberId}/program-tasks/{taskId}/survey-response",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
    public SurveyResponseDetailDto memberSurveyResponse(@PathVariable UUID orgId,
            @PathVariable UUID memberId, @PathVariable UUID taskId) {
        // `responseDetailForProgramTask` is documented as "CALLERS AUTHORIZE
        // FIRST", and the class guard only authorizes the CALLER. Since V171 a
        // cohort spans orgs, so the member named in the path has to be proved
        // to be one of THIS org's founders too.
        orgMembers.requireMemberOf(orgId, memberId);
        return resultsService.responseDetailForProgramTask(taskId, memberId);
    }
}
