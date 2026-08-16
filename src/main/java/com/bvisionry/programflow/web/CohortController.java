package com.bvisionry.programflow.web;

import java.util.List;
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

import com.bvisionry.programflow.dto.AssignOrgRequest;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CohortOrgDto;
import com.bvisionry.programflow.dto.CohortRosterEntryDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.programflow.dto.UpdateOrgAssignmentRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Platform cohort authoring (spec §13 — super admin only). Cohorts are
 * platform artifacts assigned to orgs; the org-facing participation surface
 * is {@link OrgCohortController}.
 *
 * <ul>
 *   <li>GET    /api/cohorts                         — list all</li>
 *   <li>POST   /api/cohorts                         — create DRAFT</li>
 *   <li>PUT    /api/cohorts/{cohortId}              — rename</li>
 *   <li>POST   /api/cohorts/{cohortId}/launch       — DRAFT → LAUNCHED (each assigned org pays; 409 when one is over quota)</li>
 *   <li>POST   /api/cohorts/{cohortId}/complete     — LAUNCHED → COMPLETED</li>
 *   <li>POST   /api/cohorts/{cohortId}/archive      — DRAFT/COMPLETED → ARCHIVED</li>
 *   <li>GET    /api/cohorts/{cohortId}/orgs         — org assignments</li>
 *   <li>POST   /api/cohorts/{cohortId}/orgs         — assign org (enrollment rule; launched cohort = that org pays now)</li>
 *   <li>PUT    /api/cohorts/{cohortId}/orgs/{orgId} — edit auto-enroll rule</li>
 *   <li>DELETE /api/cohorts/{cohortId}/orgs/{orgId} — unassign (no quota refund)</li>
 *   <li>DELETE /api/cohorts/{cohortId}              — delete (all its data; the launch ledger stands)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Cohorts (platform)", description = "Platform cohort authoring, lifecycle and org assignment.")
public class CohortController {

    private final CohortService service;

    public CohortController(CohortService service) {
        this.service = service;
    }

    @GetMapping
    public List<CohortDto> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CohortDto create(@Valid @RequestBody CreateCohortRequest req) {
        return service.create(req);
    }

    @PutMapping("/{cohortId}")
    public CohortDto update(@PathVariable UUID cohortId, @Valid @RequestBody UpdateCohortRequest req) {
        return service.update(cohortId, req);
    }

    /** DRAFT → LAUNCHED. Quota-checked + ledgered per assigned org in one transaction (spec §8/§13.4). */
    @PostMapping("/{cohortId}/launch")
    public CohortDto launch(@PathVariable UUID cohortId) {
        return service.launch(cohortId);
    }

    /** LAUNCHED → DRAFT: back to the drawing board. Quota is never refunded (spec §8). */
    @PostMapping("/{cohortId}/unlaunch")
    public CohortDto unlaunch(@PathVariable UUID cohortId) {
        return service.unlaunch(cohortId);
    }

    /** The roster as names — what the audience picker needs. No progress (§13.7). */
    @GetMapping("/{cohortId}/members")
    public List<CohortRosterEntryDto> roster(@PathVariable UUID cohortId) {
        return service.roster(cohortId);
    }

    @GetMapping("/{cohortId}/orgs")
    public List<CohortOrgDto> listOrgs(@PathVariable UUID cohortId) {
        return service.listOrgAssignments(cohortId);
    }

    @PostMapping("/{cohortId}/orgs")
    @ResponseStatus(HttpStatus.CREATED)
    public CohortDto assignOrg(@PathVariable UUID cohortId, @Valid @RequestBody AssignOrgRequest req) {
        return service.assignOrg(cohortId, req);
    }

    @PutMapping("/{cohortId}/orgs/{orgId}")
    public CohortDto updateOrgAssignment(
            @PathVariable UUID cohortId,
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateOrgAssignmentRequest req) {
        return service.updateOrgAssignment(cohortId, orgId, req);
    }

    @DeleteMapping("/{cohortId}/orgs/{orgId}")
    public CohortDto unassignOrg(@PathVariable UUID cohortId, @PathVariable UUID orgId) {
        return service.unassignOrg(cohortId, orgId);
    }

    @DeleteMapping("/{cohortId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID cohortId) {
        service.delete(cohortId);
    }
}
