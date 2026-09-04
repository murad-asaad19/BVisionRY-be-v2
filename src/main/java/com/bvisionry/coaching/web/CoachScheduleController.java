package com.bvisionry.coaching.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.AttendanceMarkRequest;
import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CoachAvailabilityResponse;
import com.bvisionry.coaching.dto.CoachSessionsResponse;
import com.bvisionry.coaching.dto.CompleteSessionRequest;
import com.bvisionry.coaching.dto.ScheduleSessionRequest;
import com.bvisionry.coaching.dto.UpsertAvailabilityRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The coach's calendar (spec v2 §6.1) — availability in, sessions out.
 *
 * <p>Separate from {@link CoachConsoleController} rather than bolted onto it:
 * that class is the ROSTER (who I coach and what they submitted), this one is
 * the CALENDAR, and they share nothing but the authority floor. Same
 * three-layer defense though — the {@code /api/v1/coach/**} route rule in
 * {@code SecurityConfig}, the class {@code @PreAuthorize} here, and
 * {@code coach_id = caller} (or the cohort grant) inside every query.
 */
@RestController
@RequestMapping(path = "/api/v1/coach", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Coach calendar", description = "Availability and sessions for the signed-in coach.")
public class CoachScheduleController {

    private final CoachScheduleService service;

    @GetMapping("/availability")
    public CoachAvailabilityResponse availability() {
        return service.availability();
    }

    /** Whole-calendar replace — see {@link UpsertAvailabilityRequest}. */
    @PutMapping(path = "/availability", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CoachAvailabilityResponse replaceAvailability(
            @Valid @RequestBody UpsertAvailabilityRequest request) {
        return service.replaceAvailability(request);
    }

    /** Unscheduled to-dos, upcoming appointments and the roll call, in one payload. */
    @GetMapping("/sessions")
    public CoachSessionsResponse sessions() {
        return service.sessions();
    }

    /** The caller's own free instants for a cohort-wide session, at most 8 weeks wide. */
    @GetMapping("/sessions/{sessionId}/slots")
    public BookingSlotsResponse sessionSlots(@PathVariable UUID sessionId,
                                             @RequestParam Instant from,
                                             @RequestParam Instant to) {
        return service.sessionSlots(sessionId, from, to);
    }

    /** Date a group or workshop session on the caller's calendar; re-dating one is a reschedule. */
    @PostMapping(path = "/sessions/{sessionId}/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void scheduleSession(@PathVariable UUID sessionId,
                                @Valid @RequestBody ScheduleSessionRequest request) {
        service.scheduleSession(sessionId, request.startsAt());
    }

    /** The roll call: COMPLETED with exactly these members marked present. */
    @PostMapping(path = "/sessions/{sessionId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void completeSession(@PathVariable UUID sessionId,
                                @Valid @RequestBody CompleteSessionRequest request) {
        service.complete(sessionId, request.presentMemberIds());
    }

    /** Correct one mark on a session already held. */
    @PutMapping(path = "/sessions/{sessionId}/attendance/{memberId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public void setAttendance(@PathVariable UUID sessionId, @PathVariable UUID memberId,
                              @Valid @RequestBody AttendanceMarkRequest request) {
        service.setAttendance(sessionId, memberId, request.present());
    }

    /** A 1:1 held with nobody present goes back to UNSCHEDULED so the member can rebook. */
    @PostMapping("/sessions/{sessionId}/reopen")
    public void reopenSession(@PathVariable UUID sessionId) {
        service.reopen(sessionId);
    }

    /** Cancel: the row reverts to UNSCHEDULED (spec §2.3) and the other side is emailed. */
    @DeleteMapping("/sessions/{sessionId}")
    public void cancelSession(@PathVariable UUID sessionId) {
        service.cancel(sessionId);
    }
}
