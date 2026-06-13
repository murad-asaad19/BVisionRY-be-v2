package com.bvisionry.enrollment.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.enrollment.dto.ContentProgressDto;
import com.bvisionry.enrollment.dto.EnrollmentDto;
import com.bvisionry.enrollment.dto.LearnViewDto;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Enrollment, learn-view, and progress endpoints.
 *
 * <ul>
 *   <li>POST /api/v1/courses/{slug}/enroll               — create / return enrollment</li>
 *   <li>GET  /api/v1/my/enrollments                      — list my enrollments</li>
 *   <li>GET  /api/v1/courses/{slug}/learn                — curriculum + progress</li>
 *   <li>POST /api/v1/enrollments/{id}/content/{cId}/complete — mark lesson done</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@Tag(name = "Enrollment", description = "Enrollment and course player progress.")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @PostMapping("/courses/{slug}/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentDto enroll(@PathVariable String slug) {
        return service.enroll(slug);
    }

    @GetMapping("/my/enrollments")
    public List<EnrollmentDto> myEnrollments() {
        return service.myEnrollments();
    }

    @GetMapping("/courses/{slug}/learn")
    public LearnViewDto learnView(@PathVariable String slug) {
        return service.learnView(slug);
    }

    @PostMapping("/enrollments/{id}/content/{contentId}/complete")
    public ContentProgressDto markComplete(
            @PathVariable UUID id,
            @PathVariable UUID contentId) {
        return service.markComplete(id, contentId);
    }
}
