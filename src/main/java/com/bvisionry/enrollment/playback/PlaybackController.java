package com.bvisionry.enrollment.playback;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Video playback position endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/enrollments/{enrollmentId}/content/{contentId}/position}
 *       — save current position and watched percentage; auto-completes at ≥95%.</li>
 *   <li>{@code GET  /api/v1/enrollments/{enrollmentId}/content/{contentId}/position}
 *       — retrieve saved position for resume-on-open.</li>
 * </ul>
 */
@RestController
@RequestMapping(
        path = "/api/v1/enrollments/{enrollmentId}/content/{contentId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@Validated
@Tag(name = "Playback", description = "Video resume position tracking.")
public class PlaybackController {

    private final PlaybackService service;

    public PlaybackController(PlaybackService service) {
        this.service = service;
    }

    @PostMapping("/position")
    @Operation(summary = "Save playback position",
            description = "Records the current position and watched-% for a video lesson. "
                    + "Auto-completes the lesson when watched_pct >= 95.")
    public PositionDto updatePosition(
            @PathVariable UUID enrollmentId,
            @PathVariable UUID contentId,
            @Valid @RequestBody UpdatePositionRequest req) {
        return service.updatePosition(enrollmentId, contentId,
                req.positionSeconds(), req.durationSeconds());
    }

    @GetMapping("/position")
    @Operation(summary = "Get saved playback position",
            description = "Returns the last saved position for resume-on-open. "
                    + "Returns {positionSeconds:0, watchedPct:0, completed:false} when no position has been saved yet.")
    public PositionDto getPosition(
            @PathVariable UUID enrollmentId,
            @PathVariable UUID contentId) {
        return service.getPosition(enrollmentId, contentId);
    }
}
