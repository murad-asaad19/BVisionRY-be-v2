package com.bvisionry.founderprofile.web;

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
import com.bvisionry.founderprofile.dto.FounderProfileResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The coach's founder profile read (spec §2.4) — same payload the org admin
 * gets, gated by the shared {@link CoachAccess} assignment-union predicate
 * (a founder outside the union is a 404). Route floor {@code /api/v1/coach/**}
 * requires COACH in SecurityConfig; the class re-asserts it.
 */
@RestController
@RequestMapping(path = "/api/v1/coach/founders/{founderId}/profile",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Founder profile (coach)", description = "The shared founder profile for a coach.")
public class FounderProfileCoachController {

    private final FounderProfileService service;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public FounderProfileResponse coachFounderProfile(@PathVariable UUID founderId) {
        CoachAccess.ViewedFounder coach = coachAccess.requireSees(founderId);
        return service.profile(coach.orgId(), founderId);
    }
}
