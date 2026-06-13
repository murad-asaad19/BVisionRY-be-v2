package com.bvisionry.enrollment.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.enrollment.dto.LessonContentDto;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Returns the full lesson content payload (body + media URLs).
 *
 * <ul>
 *   <li>GET /api/v1/courses/{slug}/content/{contentId} — requires authentication</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/courses/{slug}/content", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Enrollment", description = "Lesson content (body + media).")
public class LessonContentController {

    private final EnrollmentService service;

    public LessonContentController(EnrollmentService service) {
        this.service = service;
    }

    @GetMapping("/{contentId}")
    public LessonContentDto lessonContent(
            @PathVariable String slug,
            @PathVariable UUID contentId) {
        return service.lessonContent(slug, contentId);
    }
}
