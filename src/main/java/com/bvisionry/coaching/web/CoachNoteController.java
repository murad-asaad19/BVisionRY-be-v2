package com.bvisionry.coaching.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.CoachNoteRequest;
import com.bvisionry.coaching.dto.CoachNoteResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Coach note writes (redesign spec §2.2/§2.4). Route floor
 * {@code /api/v1/coach/**} requires COACH in SecurityConfig; the class
 * re-asserts it. There is deliberately NO member-facing route for notes —
 * members must never see them — and reads live on the founder-profile
 * aggregation endpoints.
 */
@RestController
@RequestMapping(path = "/api/v1/coach", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Coach notes", description = "Private notes a coach keeps about a founder.")
public class CoachNoteController {

    private final CoachNoteService service;

    @PostMapping(path = "/founders/{founderId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CoachNoteResponse createCoachNote(@PathVariable UUID founderId,
            @Valid @RequestBody CoachNoteRequest request) {
        return service.create(founderId, request);
    }

    @PatchMapping(path = "/notes/{noteId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CoachNoteResponse updateCoachNote(@PathVariable UUID noteId,
            @Valid @RequestBody CoachNoteRequest request) {
        return service.update(noteId, request);
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCoachNote(@PathVariable UUID noteId) {
        service.delete(noteId);
    }
}
