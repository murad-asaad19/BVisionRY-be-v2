package com.bvisionry.catalog.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.dto.AdminCourseDetailDto;
import com.bvisionry.catalog.dto.CourseAdminDto;
import com.bvisionry.catalog.dto.authoring.ReorderRequest;
import com.bvisionry.catalog.dto.authoring.SetCourseStateRequest;
import com.bvisionry.catalog.dto.authoring.UpsertContentRequest;
import com.bvisionry.catalog.dto.authoring.UpsertCourseRequest;
import com.bvisionry.catalog.dto.authoring.UpsertSectionRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Authoring write endpoints. All require SUPER_ADMIN or INSTRUCTOR role.
 *
 * <ul>
 *   <li>POST   /api/v1/courses/{slug}/sections           — create section</li>
 *   <li>PUT    /api/v1/sections/{id}                     — update section</li>
 *   <li>DELETE /api/v1/sections/{id}                     — delete section</li>
 *   <li>PUT    /api/v1/courses/{slug}/sections/reorder   — reorder sections</li>
 *   <li>POST   /api/v1/sections/{sectionId}/content      — create content</li>
 *   <li>PUT    /api/v1/content/{id}                      — update content</li>
 *   <li>DELETE /api/v1/content/{id}                      — delete content</li>
 *   <li>PUT    /api/v1/sections/{id}/content/reorder     — reorder content</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'INSTRUCTOR')")
@Tag(name = "Authoring", description = "Course authoring write endpoints (SUPER_ADMIN / INSTRUCTOR).")
public class AuthoringController {

    private final AuthoringService authoring;

    public AuthoringController(AuthoringService authoring) {
        this.authoring = authoring;
    }

    // -------------------------------------------------------------------------
    // Courses (course-level CRUD + publish lifecycle)
    // -------------------------------------------------------------------------

    /** Authoring list — includes DRAFT/ARCHIVED. SUPER_ADMIN sees all orgs; INSTRUCTOR their org. */
    @GetMapping("/admin/courses")
    public List<CourseAdminDto> listCourses() {
        return authoring.listForAuthoring();
    }

    /** Full editable course detail for the editor — works for ANY state (DRAFT included). */
    @GetMapping("/admin/courses/{slug}")
    public AdminCourseDetailDto getCourseForEditing(@PathVariable String slug) {
        return authoring.getForEditing(slug);
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseAdminDto createCourse(@Valid @RequestBody UpsertCourseRequest req) {
        return authoring.createCourse(req);
    }

    @PutMapping("/courses/{slug}")
    public CourseAdminDto updateCourse(
            @PathVariable String slug,
            @Valid @RequestBody UpsertCourseRequest req) {
        return authoring.updateCourse(slug, req);
    }

    @DeleteMapping("/courses/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCourse(@PathVariable String slug) {
        authoring.deleteCourse(slug);
    }

    /** Publish / unpublish / archive: DRAFT ↔ PUBLISHED ↔ ARCHIVED. */
    @PostMapping("/courses/{slug}/state")
    public CourseAdminDto setCourseState(
            @PathVariable String slug,
            @Valid @RequestBody SetCourseStateRequest req) {
        return authoring.setState(slug, req.state());
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    @PostMapping("/courses/{slug}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public Section createSection(
            @PathVariable String slug,
            @Valid @RequestBody UpsertSectionRequest req) {
        return authoring.createSection(slug, req);
    }

    @PutMapping("/sections/{id}")
    public Section updateSection(
            @PathVariable String id,
            @Valid @RequestBody UpsertSectionRequest req) {
        return authoring.updateSection(id, req);
    }

    @DeleteMapping("/sections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(@PathVariable String id) {
        authoring.deleteSection(id);
    }

    @PutMapping("/courses/{slug}/sections/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderSections(
            @PathVariable String slug,
            @Valid @RequestBody ReorderRequest req) {
        authoring.reorderSections(slug, req);
    }

    // -------------------------------------------------------------------------
    // Content / Lessons
    // -------------------------------------------------------------------------

    @PostMapping("/sections/{sectionId}/content")
    @ResponseStatus(HttpStatus.CREATED)
    public Content createContent(
            @PathVariable String sectionId,
            @Valid @RequestBody UpsertContentRequest req) {
        return authoring.createContent(sectionId, req);
    }

    @PutMapping("/content/{id}")
    public Content updateContent(
            @PathVariable String id,
            @Valid @RequestBody UpsertContentRequest req) {
        return authoring.updateContent(id, req);
    }

    @DeleteMapping("/content/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContent(@PathVariable String id) {
        authoring.deleteContent(id);
    }

    @PutMapping("/sections/{id}/content/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderContents(
            @PathVariable String id,
            @Valid @RequestBody ReorderRequest req) {
        authoring.reorderContents(id, req);
    }
}
