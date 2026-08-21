package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.RecommendedCourseResponse;
import com.bvisionry.pipeline.service.FounderRecommendationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The founder's own recommendations — "recommended because of &lt;pillar&gt;".
 *
 * <p><strong>{@code /api/my/…}, not {@code /api/pipelines/…}, on purpose.</strong>
 * The pillar-authoring subtree {@code /api/pipelines/*&#47;pillars/**} is floored at
 * SUPER_ADMIN in {@code SecurityConfig}, so a founder-readable route under it would
 * be 403ed by the route layer — or would cost that floor an exception, which is the
 * opposite of what it exists for. {@code /api/my/**} is where every other
 * self-service surface lives (assessments, program, workshops, exercises, results),
 * and it carries the {@code anyRequest().authenticated()} floor. The path itself
 * names no user id: the caller cannot ask about anybody but themselves, because
 * there is nowhere to say who.
 *
 * <p>No parameters, deliberately: the results page wants one assessment's
 * recommendations and filters this list by {@code submissionId} client-side. A
 * founder has a handful of rows, so a query parameter would buy a second code path
 * and a second thing to get the scoping right on.
 */
@RestController
@RequestMapping(path = "/api/my/recommendations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Recommendations (founder)", description = "Courses a founder's assessment enrolled them in, with the pillar behind each.")
public class MyRecommendationController {

    private final FounderRecommendationService service;

    @GetMapping
    public List<RecommendedCourseResponse> myRecommendations() {
        return service.myRecommendations();
    }
}
