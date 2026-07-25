package com.bvisionry.programflow.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.domain.ProgramSurface;
import com.bvisionry.programflow.dto.ProgramOrgDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Org directory for the Program Flow and Workshops consoles: every
 * sub-organization with its parent name, console membership and learner /
 * cohort / workshop counts. Each switcher lists the orgs on its surface; the
 * "add organization" pickers offer the rest.
 */
@RestController
@RequestMapping(path = "/api/program-flow/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Program Flow (admin)", description = "Org directory for the Program Flow console.")
public class ProgramOrgsController {

    private final CohortService service;

    public ProgramOrgsController(CohortService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProgramOrgDto> list() {
        return service.listOrgs();
    }

    @PutMapping("/{orgId}/surfaces/{surface}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "List the organization on a console",
            description = "Idempotent. Any cohorts or workshops it already owns are reused as-is.")
    public void addToSurface(@PathVariable UUID orgId, @PathVariable ProgramSurface surface) {
        service.addToSurface(orgId, surface);
    }

    @DeleteMapping("/{orgId}/surfaces/{surface}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take the organization off a console",
            description = "Non-destructive: cohorts, workshops and learner data are kept, so "
                    + "re-adding the organization restores it. Deleting that data is a separate call.")
    public void removeFromSurface(@PathVariable UUID orgId, @PathVariable ProgramSurface surface) {
        service.removeFromSurface(orgId, surface);
    }
}
