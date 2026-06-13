package com.bvisionry.catalog.embed;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Player endpoints for ASSIGNMENT-type lessons that embed an FRI pipeline
 * assessment. Backed by {@link EmbeddedAssessmentService}.
 *
 * <ul>
 *   <li>POST /api/v1/courses/{slug}/content/{contentId}/assessment/start</li>
 *   <li>GET  /api/v1/courses/{slug}/content/{contentId}/assessment</li>
 * </ul>
 */
@RestController
@RequestMapping(
        path = "/api/v1/courses/{slug}/content/{contentId}/assessment",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "Embedded Assessment", description = "ASSIGNMENT-type lesson assessment resolution and lazy creation.")
public class EmbeddedAssessmentController {

    private final EmbeddedAssessmentService service;

    /**
     * Resolves or lazily creates the member's Assignment+Submission for the
     * pipeline embedded in this ASSIGNMENT lesson, then returns the link DTO.
     *
     * <p>Returns 400 if the content is not an ASSIGNMENT type or has no pipeline linked.
     */
    @PostMapping("/start")
    @Operation(summary = "Start (or resume) the embedded assessment for an ASSIGNMENT lesson")
    public AssessmentLinkDto start(
            @PathVariable String slug,
            @PathVariable UUID contentId) {
        return service.startAssessment(slug, contentId);
    }

    /**
     * Read-only resolve of the member's current submission for the embedded pipeline.
     * Returns {@code assigned=false} when no submission exists (no creation).
     * Marks the lesson complete when the submission status is SUBMITTED or EVALUATED.
     */
    @GetMapping
    @Operation(summary = "Resolve the member's current submission status for an ASSIGNMENT lesson")
    public AssessmentLinkDto resolve(
            @PathVariable String slug,
            @PathVariable UUID contentId) {
        return service.resolveAssessment(slug, contentId);
    }
}
