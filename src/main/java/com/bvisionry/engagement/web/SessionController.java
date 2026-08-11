package com.bvisionry.engagement.web;

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

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.engagement.dto.SessionDtos.AttendanceRequest;
import com.bvisionry.engagement.dto.SessionDtos.CohortSessionsResponse;
import com.bvisionry.engagement.dto.SessionDtos.SessionDto;
import com.bvisionry.engagement.dto.SessionDtos.UpsertSessionRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The cohort board Sessions tab (spec §4): session CRUD + roll call. Spec
 * §13: the board is platform-scoped, super admin only. Attendance is entered
 * here — coaches read engagement through the founder profile, members never
 * see it.
 */
@RestController
@RequestMapping(path = "/api/cohorts/{cohortId}/sessions",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Sessions (admin)", description = "Cohort sessions and attendance roll call.")
public class SessionController {

    private final SessionService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public CohortSessionsResponse list(@PathVariable UUID cohortId) {
        return service.list(cohortId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto create(@PathVariable UUID cohortId,
            @Valid @RequestBody UpsertSessionRequest req) {
        return service.create(cohortId, req, currentUser.require().userId());
    }

    @PutMapping("/{sessionId}")
    public SessionDto update(@PathVariable UUID cohortId,
            @PathVariable UUID sessionId, @Valid @RequestBody UpsertSessionRequest req) {
        return service.update(cohortId, sessionId, req);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID cohortId,
            @PathVariable UUID sessionId) {
        service.delete(cohortId, sessionId);
    }

    /** The roll-call tick/untick — §7b stamped with who marked and when. */
    @PutMapping("/{sessionId}/attendance/{memberId}")
    public SessionDto setAttendance(@PathVariable UUID cohortId,
            @PathVariable UUID sessionId, @PathVariable UUID memberId,
            @Valid @RequestBody AttendanceRequest req) {
        return service.setAttendance(cohortId, sessionId, memberId, req.present(),
                currentUser.require().userId());
    }
}
