package com.bvisionry.coaching.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CohortSessionSchedulingResponse;
import com.bvisionry.coaching.dto.ScheduleSessionRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Scheduling a cohort-wide session as the platform (spec v2 §6.2) — the org
 * console's "Schedule" action on an undated group or workshop row.
 *
 * <p>SUPER_ADMIN only, and stated here rather than in {@code SecurityConfig}:
 * this is the platform-builder half of the §13.x split (authoring and the
 * cohort board are Bvisionry's), so the authority is a property of the surface,
 * not of a URL prefix an org route might later share.
 *
 * <p>The verbs are {@link CohortSessionSchedulingService}'s, unchanged — the
 * only difference from the coach's own routes is that a super admin names WHICH
 * coach's calendar the session lands on, and the service checks that coach
 * against the cohort's grants either way.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/sessions/{sessionId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Session scheduling (admin)",
        description = "Date a cohort-wide session on a cohort coach's calendar.")
public class AdminSessionSchedulingController {

    private final CohortSessionSchedulingService service;

    /** The session's type, duration and the coaches who may run it (spec §5). */
    @GetMapping("/scheduling")
    public CohortSessionSchedulingResponse scheduling(@PathVariable UUID sessionId) {
        return service.scheduling(sessionId);
    }

    /** One coach's free instants for this session, at most 8 weeks wide. */
    @GetMapping("/slots")
    public BookingSlotsResponse slots(@PathVariable UUID sessionId,
                                      @RequestParam UUID coachId,
                                      @RequestParam Instant from,
                                      @RequestParam Instant to) {
        return service.slots(sessionId, coachId, from, to);
    }

    @PostMapping(path = "/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void schedule(@PathVariable UUID sessionId,
                         @Valid @RequestBody ScheduleSessionRequest request) {
        service.schedule(sessionId, request.coachId(), request.startsAt());
    }

    /** Cancel: back to UNSCHEDULED, every attendee emailed. */
    @DeleteMapping
    public void cancel(@PathVariable UUID sessionId) {
        service.cancel(sessionId, false);
    }
}
