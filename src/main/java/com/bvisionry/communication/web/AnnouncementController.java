package com.bvisionry.communication.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.communication.dto.AnnouncementCohortResponse;
import com.bvisionry.communication.dto.AnnouncementResponse;
import com.bvisionry.communication.dto.CreateAnnouncementRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The author side of cohort announcements: an ORG_ADMIN broadcasts to any
 * cohort in their org (hierarchy included), a COACH to a cohort they are
 * assigned to. Announcements only — no threads, no replies, no DMs (policy
 * {@code decisions.communications: ANNOUNCEMENTS_ONLY}).
 *
 * <p>Three-layer defense: the route pattern admits only the three authoring
 * roles in {@code SecurityConfig}, {@code @PreAuthorize} re-asserts the role
 * AND pins the caller to the path org via {@code @orgAccess}, and
 * {@link AnnouncementService} re-resolves the cohort inside that org plus the
 * coach's grant before anything is written.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'ORG_ADMIN', 'COACH') and @orgAccess.isInOrg(#orgId)")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Cohort broadcasts by an org admin or an assigned coach.")
public class AnnouncementController {

    private final AnnouncementService service;

    /** The cohorts this caller may broadcast to — never a wider list than they may post to. */
    @GetMapping("/announcement-cohorts")
    public List<AnnouncementCohortResponse> broadcastTargets(@PathVariable UUID orgId) {
        return service.broadcastTargets(orgId);
    }

    @GetMapping("/cohorts/{cohortId}/announcements")
    public List<AnnouncementResponse> feed(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return service.feed(orgId, cohortId);
    }

    @PostMapping("/cohorts/{cohortId}/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementResponse post(@PathVariable UUID orgId, @PathVariable UUID cohortId,
                                     @Valid @RequestBody CreateAnnouncementRequest request) {
        return service.post(orgId, cohortId, request);
    }
}
