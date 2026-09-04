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

import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CreateBookingRequest;
import com.bvisionry.coaching.dto.MemberBookingResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The member's booking screen for a SESSION journey task (spec v2 §6.3).
 *
 * <p>Mounted under {@code /api/my/program/tasks/{taskId}} alongside the other
 * per-task member routes ({@code /survey}, {@code /submit}, {@code /open}) even
 * though the logic is the coaching slice's, because the URL a founder's browser
 * hits should follow their journey, not our package layout — the same call
 * {@code MemberProgramSurveyController} makes from the survey slice.
 *
 * <p>The caller's identity is the scope: no id in the path names a person, and
 * the task read carries the enrollment + audience predicates, so a task in
 * someone else's cohort is a 404. Reschedule is DELETE + POST, not a verb.
 */
@RestController
@RequestMapping(path = "/api/my/program/tasks/{taskId}/booking",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My coaching booking", description = "Book, view and cancel a 1:1 for a session task.")
public class MemberBookingController {

    private final MemberBookingService service;

    @GetMapping
    public MemberBookingResponse booking(@PathVariable UUID taskId) {
        return service.booking(taskId);
    }

    /** Free instants for one coach in {@code [from, to)} — at most 8 weeks wide. */
    @GetMapping("/slots")
    public BookingSlotsResponse slots(@PathVariable UUID taskId,
                                      @RequestParam UUID coachId,
                                      @RequestParam Instant from,
                                      @RequestParam Instant to) {
        return service.slots(taskId, coachId, from, to);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberBookingResponse book(@PathVariable UUID taskId,
                                      @Valid @RequestBody CreateBookingRequest request) {
        return service.book(taskId, request.coachId(), request.startsAt());
    }

    /**
     * Move own SCHEDULED, not-yet-started booking to another coach/slot in one
     * step (409 otherwise). Same body as the POST — the UI's Reschedule uses
     * this rather than DELETE + POST, so the founder is never briefly unbooked.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberBookingResponse reschedule(@PathVariable UUID taskId,
                                            @Valid @RequestBody CreateBookingRequest request) {
        return service.reschedule(taskId, request.coachId(), request.startsAt());
    }

    /** Cancel own SCHEDULED booking before it starts (back to UNSCHEDULED); 409 otherwise. */
    @DeleteMapping
    public void cancel(@PathVariable UUID taskId) {
        service.cancel(taskId);
    }
}
