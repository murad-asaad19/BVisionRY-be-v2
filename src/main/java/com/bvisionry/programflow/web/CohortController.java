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

import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin cohort management for an org.
 *
 * <ul>
 *   <li>GET    /api/organizations/{orgId}/cohorts                    — list cohorts</li>
 *   <li>POST   /api/organizations/{orgId}/cohorts                    — create cohort</li>
 *   <li>PUT    /api/organizations/{orgId}/cohorts/{cohortId}         — rename / set status</li>
 *   <li>PUT    /api/organizations/{orgId}/cohorts/{cohortId}/members — set enrolment</li>
 *   <li>DELETE /api/organizations/{orgId}/cohorts/{cohortId}         — delete (all its data)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Cohorts (admin)", description = "Cohort roster and lifecycle.")
public class CohortController {

    private final CohortService service;

    public CohortController(CohortService service) {
        this.service = service;
    }

    @GetMapping
    public List<CohortDto> list(@PathVariable UUID orgId) {
        return service.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CohortDto create(@PathVariable UUID orgId, @Valid @RequestBody CreateCohortRequest req) {
        return service.create(orgId, req);
    }

    @PutMapping("/{cohortId}")
    public CohortDto update(
            @PathVariable UUID orgId,
            @PathVariable UUID cohortId,
            @Valid @RequestBody UpdateCohortRequest req) {
        return service.update(orgId, cohortId, req);
    }

    @PutMapping("/{cohortId}/members")
    public CohortDto setMembers(
            @PathVariable UUID orgId,
            @PathVariable UUID cohortId,
            @Valid @RequestBody UpdateCohortMembersRequest req) {
        return service.setMembers(orgId, cohortId, req);
    }

    @DeleteMapping("/{cohortId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        service.delete(orgId, cohortId);
    }
}
