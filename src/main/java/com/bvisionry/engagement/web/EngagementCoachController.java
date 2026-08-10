package com.bvisionry.engagement.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.engagement.dto.EngagementRecordResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The coach's engagement-record read (spec §4) — same payload the org admin
 * gets, gated by the shared {@link CoachAccess} assignment-union predicate
 * (a founder outside the union is a 404). Read-only: attendance is entered on
 * the cohort board by admins.
 */
@RestController
@RequestMapping(path = "/api/v1/coach/founders/{founderId}/engagement",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Engagement (coach)", description = "Participation score + attendance history for an assigned founder.")
public class EngagementCoachController {

    private final EngagementService service;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public EngagementRecordResponse founderEngagement(@PathVariable UUID founderId) {
        CurrentUser coach = currentUser.require();
        if (!coachAccess.coachSees(coach.orgId(), coach.userId(), founderId)) {
            throw new ResourceNotFoundException("Founder", founderId.toString());
        }
        return service.record(coach.orgId(), founderId);
    }
}
