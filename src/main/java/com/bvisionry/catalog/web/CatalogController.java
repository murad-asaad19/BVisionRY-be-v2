package com.bvisionry.catalog.web;

import java.math.BigDecimal;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.catalog.dto.CourseDetailDto;
import com.bvisionry.catalog.dto.CourseListResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public course catalog endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/v1/courses} — filtered list, returns
 *       {@code { items, total }}. No authentication required.</li>
 *   <li>{@code GET /api/v1/courses/{slug}} — single course detail, or a 404
 *       response body for an unknown/unpublished slug. No authentication required.</li>
 * </ul>
 *
 * <p>Both endpoints are declared as {@code permitAll()} in
 * {@link com.bvisionry.config.SecurityConfig} — no JWT is needed.
 */
@RestController
@RequestMapping(path = "/api/v1/courses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalog", description = "Public course catalog.")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "List catalog courses",
            description = "Returns all published courses matching the optional filters. "
                    + "All query parameters are optional; omit one to ignore it. "
                    + "No authentication required.")
    public CourseListResponse list(
            @Parameter(description = "Free-text search over title, subtitle, category and tags.")
            @RequestParam(required = false) String q,

            @Parameter(description = "Exact (case-insensitive) category match, e.g. 'Leadership'.")
            @RequestParam(required = false) String category,

            @Parameter(description = "Difficulty level.",
                    schema = @Schema(allowableValues = {"BEGINNER", "INTERMEDIATE", "ADVANCED"}))
            @RequestParam(required = false) String level,

            @Parameter(description = "Audience channel (contract field `mode`).",
                    schema = @Schema(allowableValues = {"EMPLOYEE", "PUBLIC", "B2B"}))
            @RequestParam(required = false) String mode,

            @Parameter(description = "Upper price bound; free courses always match.")
            @RequestParam(required = false) BigDecimal maxPrice) {

        return catalog.list(q, category, level, mode, maxPrice);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a course by slug",
            description = "Returns the full course detail for a published course. "
                    + "No authentication required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Course found"),
            @ApiResponse(responseCode = "404", description = "No published course for that slug",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    public CourseDetailDto getBySlug(
            @Parameter(description = "URL slug, e.g. 'leadership-essentials-new-managers'.")
            @PathVariable String slug) {
        return catalog.getBySlug(slug);
    }
}
