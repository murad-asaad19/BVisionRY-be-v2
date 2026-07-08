package com.bvisionry.workshops.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.workshops.dto.AssignMemberRequest;
import com.bvisionry.workshops.dto.SetLeadRequest;
import com.bvisionry.workshops.dto.TeamCardRequest;
import com.bvisionry.workshops.dto.TeamNameRequest;
import com.bvisionry.workshops.dto.WorkshopTeamsResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Admin management of a workshop's teams, membership and team leads. */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/workshops/{workshopId}/teams",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Workshop teams (admin)", description = "Per-workshop teams, membership, leads.")
public class WorkshopTeamController {

    private final WorkshopTeamService service;

    public WorkshopTeamController(WorkshopTeamService service) {
        this.service = service;
    }

    @GetMapping
    public WorkshopTeamsResponse teams(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        return service.teams(orgId, workshopId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                       @Valid @RequestBody TeamNameRequest req) {
        service.createTeam(orgId, workshopId, req);
    }

    @PutMapping("/{teamId}")
    public void rename(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                       @PathVariable UUID teamId, @Valid @RequestBody TeamNameRequest req) {
        service.renameTeam(orgId, workshopId, teamId, req);
    }

    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                       @PathVariable UUID teamId) {
        service.deleteTeam(orgId, workshopId, teamId);
    }

    @PutMapping("/membership")
    public void assign(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                       @Valid @RequestBody AssignMemberRequest req) {
        service.assignMember(orgId, workshopId, req);
    }

    @PutMapping("/{teamId}/card")
    public void setCard(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                        @PathVariable UUID teamId, @Valid @RequestBody TeamCardRequest req) {
        service.setCard(orgId, workshopId, teamId, req.card());
    }

    @PutMapping("/{teamId}/lead")
    public void setLead(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                        @PathVariable UUID teamId, @Valid @RequestBody SetLeadRequest req) {
        service.setLead(orgId, workshopId, teamId, req.userId());
    }

    /** Wipe one member's answers across the workshop so they can redo their tasks. */
    @DeleteMapping("/members/{userId}/answers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetMemberAnswers(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                                   @PathVariable UUID userId) {
        service.resetMemberAnswers(orgId, workshopId, userId);
    }

    /** Dismiss the team's "needs help" alert on the live board (re-arms their ping). */
    @DeleteMapping("/{teamId}/help")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissHelp(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                            @PathVariable UUID teamId) {
        service.dismissHelp(orgId, workshopId, teamId);
    }
}
