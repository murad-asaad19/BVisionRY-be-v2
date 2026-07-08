package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.TeamNameRequest;
import com.bvisionry.programflow.dto.TeamsResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Org-scoped team management for the program flow.
 *
 * <ul>
 *   <li>GET    /api/organizations/{orgId}/teams                          — teams + unassigned pool</li>
 *   <li>POST   /api/organizations/{orgId}/teams                          — create team</li>
 *   <li>PATCH  /api/organizations/{orgId}/teams/{teamId}                 — rename team</li>
 *   <li>DELETE /api/organizations/{orgId}/teams/{teamId}                 — delete empty team</li>
 *   <li>PUT    /api/organizations/{orgId}/teams/{teamId}/members/{userId} — add/move member</li>
 *   <li>DELETE /api/organizations/{orgId}/teams/members/{userId}         — unassign member</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/teams", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Teams", description = "Org-scoped teams & members management.")
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping
    public TeamsResponse list(@PathVariable UUID orgId) {
        return service.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamsResponse.TeamDto create(
            @PathVariable UUID orgId,
            @Valid @RequestBody TeamNameRequest req) {
        return service.create(orgId, req.name());
    }

    @PatchMapping("/{teamId}")
    public TeamsResponse.TeamDto rename(
            @PathVariable UUID orgId,
            @PathVariable UUID teamId,
            @Valid @RequestBody TeamNameRequest req) {
        return service.rename(orgId, teamId, req.name());
    }

    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID teamId) {
        service.delete(orgId, teamId);
    }

    @PutMapping("/{teamId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignMember(
            @PathVariable UUID orgId,
            @PathVariable UUID teamId,
            @PathVariable UUID userId) {
        service.assignMember(orgId, teamId, userId);
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassignMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        service.unassignMember(orgId, userId);
    }
}
