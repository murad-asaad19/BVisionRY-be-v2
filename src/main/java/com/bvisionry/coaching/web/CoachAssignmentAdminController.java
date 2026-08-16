package com.bvisionry.coaching.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.CoachAssignmentResponse;
import com.bvisionry.coaching.dto.CreateCoachAssignmentRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Org-admin coach-grant management.
 *
 * <p>Three-layer defense: the route pattern requires ORG_ADMIN/SUPER_ADMIN in
 * {@code SecurityConfig}, {@code @PreAuthorize} + {@code @orgAccess} pin the
 * caller to the path org, and the service resolves every referenced row with
 * the org predicate.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/coach-assignments",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Coach assignments (admin)", description = "Assign coaches to cohorts and founders.")
public class CoachAssignmentAdminController {

    private final CoachAssignmentAdminService service;

    @GetMapping
    public List<CoachAssignmentResponse> list(@PathVariable UUID orgId) {
        return service.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CoachAssignmentResponse create(@PathVariable UUID orgId,
                                          @Valid @RequestBody CreateCoachAssignmentRequest request) {
        return service.create(orgId, request);
    }

    @DeleteMapping("/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID assignmentId) {
        service.delete(orgId, assignmentId);
    }
}
