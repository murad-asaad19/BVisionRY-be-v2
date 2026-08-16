package com.bvisionry.coaching.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.CoachFounderDetailResponse;
import com.bvisionry.coaching.dto.CoachProfileResponse;
import com.bvisionry.coaching.dto.CoachReviewQueueResponse;
import com.bvisionry.coaching.dto.CoachRosterResponse;
import com.bvisionry.coaching.dto.UpdateCoachProfileRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The coach console (Growth "Mentor/Organization access portal").
 *
 * <p>Three-layer defense: the route pattern {@code /api/v1/coach/**} requires
 * the COACH authority in {@code SecurityConfig}, this class re-asserts it via
 * {@code @PreAuthorize}, and every query in {@link CoachConsoleService} carries
 * the caller's assignment-union predicate in SQL — the coach's identity IS the
 * scope, so there is no org/founder path parameter to widen.
 */
@RestController
@RequestMapping(path = "/api/v1/coach", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Coach console", description = "Roster and founder detail for the signed-in coach.")
public class CoachConsoleController {

    private final CoachConsoleService service;

    @GetMapping("/roster")
    public CoachRosterResponse roster() {
        return service.roster();
    }

    /** The review queue — SUBMITTED exercises across the caller's founders, oldest first. */
    @GetMapping("/queue")
    public CoachReviewQueueResponse queue() {
        return service.queue();
    }

    @GetMapping("/founders/{founderId}")
    public CoachFounderDetailResponse founderDetail(@PathVariable UUID founderId) {
        return service.founderDetail(founderId);
    }

    /**
     * The caller's own bookable profile. It lives on THIS controller rather
     * than on a {@code /users/{id}} shape for one reason: here the coach's
     * identity is the scope, so there is no id in the path for a coach to swap
     * for a colleague's, and the COACH route floor + class {@code @PreAuthorize}
     * already stand in front of it.
     */
    @GetMapping("/profile")
    public CoachProfileResponse profile() {
        return service.profile();
    }

    // Named updateBookingProfile, not updateProfile: springdoc derives the
    // operationId from the method name, and MemberController already owns
    // "updateProfile" — a collision renames THAT one to updateProfile_1 in the
    // generated client, which is churn on an unrelated surface.
    @PatchMapping(path = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CoachProfileResponse updateBookingProfile(@Valid @RequestBody UpdateCoachProfileRequest request) {
        return service.updateProfile(request);
    }
}
