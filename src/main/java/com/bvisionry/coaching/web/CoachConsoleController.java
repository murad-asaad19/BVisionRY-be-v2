package com.bvisionry.coaching.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.CoachFounderDetailResponse;
import com.bvisionry.coaching.dto.CoachRosterResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The coach console (Growth "Mentor/Organization access portal").
 *
 * <p>Three-layer defense: the route pattern {@code /api/v1/coach/**} requires
 * the COACH authority in {@code SecurityConfig}, this class re-asserts it via
 * {@code @PreAuthorize}, and every query in {@link CoachConsoleService} carries
 * the caller's assignment-union predicate in SQL — the coach's identity IS the
 * scope, so there is no org/founder path parameter to widen.
 */
@RestController
@RequestMapping(path = "/api/v1/coach", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Coach console", description = "Roster and founder detail for the signed-in coach.")
public class CoachConsoleController {

    private final CoachConsoleService service;

    @GetMapping("/roster")
    public CoachRosterResponse roster() {
        return service.roster();
    }

    @GetMapping("/founders/{founderId}")
    public CoachFounderDetailResponse founderDetail(@PathVariable UUID founderId) {
        return service.founderDetail(founderId);
    }
}
