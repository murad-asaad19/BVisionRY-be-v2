package com.bvisionry.catalog.review;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Learner course review endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/courses/{slug}/reviews}
 *       — create or update the current user's review (enrolled users only).</li>
 *   <li>{@code GET  /api/v1/courses/{slug}/reviews/me}
 *       — retrieve the current user's existing review, or 404.</li>
 * </ul>
 */
@RestController
@RequestMapping(
        path = "/api/v1/courses/{slug}/reviews",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@Validated
@Tag(name = "Reviews", description = "Learner course reviews.")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create or update own review",
            description = "Creates a new review or overwrites the caller's existing one for the course. "
                    + "The caller must be enrolled; otherwise 403 is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review saved"),
            @ApiResponse(responseCode = "403", description = "Not enrolled in this course"),
            @ApiResponse(responseCode = "404", description = "Course not found")
    })
    public ReviewDto upsertReview(
            @PathVariable String slug,
            @Valid @RequestBody UpsertReviewRequest req) {
        return service.upsert(slug, req.rating(), req.comment());
    }

    @GetMapping("/me")
    @Operation(summary = "Get own review",
            description = "Returns the current user's review for this course, or 404 if none exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review found"),
            @ApiResponse(responseCode = "404", description = "No review from the current user for this course")
    })
    public ReviewDto getMyReview(@PathVariable String slug) {
        return service.getMyReview(slug);
    }
}
