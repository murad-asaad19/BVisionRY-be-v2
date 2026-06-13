package com.bvisionry.quiz.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.quiz.dto.QuizAttemptResultDto;
import com.bvisionry.quiz.dto.QuizDto;
import com.bvisionry.quiz.dto.QuizTakingDto;
import com.bvisionry.quiz.dto.SubmitQuizAttemptRequest;
import com.bvisionry.quiz.dto.UpsertQuizRequest;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Quiz endpoints:
 * <ul>
 *   <li>GET  /api/v1/content/{contentId}/quiz                                  — authoring read</li>
 *   <li>PUT  /api/v1/content/{contentId}/quiz                                  — authoring upsert</li>
 *   <li>GET  /api/v1/courses/{slug}/content/{contentId}/quiz                   — learner take</li>
 *   <li>POST /api/v1/enrollments/{enrollmentId}/content/{contentId}/quiz/attempts — submit attempt</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quiz", description = "Quiz authoring, taking, and auto-grading.")
public class QuizController {

    private final QuizService service;

    public QuizController(QuizService service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Authoring (SUPER_ADMIN or INSTRUCTOR)
    // -------------------------------------------------------------------------

    @GetMapping("/content/{contentId}/quiz")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','INSTRUCTOR')")
    public QuizDto getForAuthoring(@PathVariable UUID contentId) {
        return service.getForAuthoring(contentId);
    }

    @PutMapping("/content/{contentId}/quiz")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','INSTRUCTOR')")
    public QuizDto upsert(
            @PathVariable UUID contentId,
            @Validated @RequestBody UpsertQuizRequest request) {
        return service.upsert(contentId, request);
    }

    // -------------------------------------------------------------------------
    // Taking (any authenticated user)
    // -------------------------------------------------------------------------

    @GetMapping("/courses/{slug}/content/{contentId}/quiz")
    @PreAuthorize("isAuthenticated()")
    public QuizTakingDto getForTaking(
            @PathVariable String slug,
            @PathVariable UUID contentId) {
        // The service binds the quiz to the {slug} course + org and enforces
        // enrollment, so the path slug must be passed through (not dropped).
        return service.getForTaking(slug, contentId);
    }

    @PostMapping("/enrollments/{enrollmentId}/content/{contentId}/quiz/attempts")
    @PreAuthorize("isAuthenticated()")
    public QuizAttemptResultDto submitAttempt(
            @PathVariable UUID enrollmentId,
            @PathVariable UUID contentId,
            @Validated @RequestBody SubmitQuizAttemptRequest request) {
        return service.submitAttempt(enrollmentId, contentId, request);
    }
}
