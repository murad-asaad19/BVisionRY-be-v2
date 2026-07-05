package com.bvisionry.programflow.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.ProgramOrgDto;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Program Flow org directory: every organization with its learner + cohort
 * counts. The switcher shows orgs already in Program Flow (cohortCount &gt; 0);
 * the "add organization" picker offers the rest.
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
}
