package com.bvisionry.courseaccess.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.courseaccess.dto.CourseVisibilityView;
import com.bvisionry.courseaccess.dto.OrgOptionView;
import com.bvisionry.courseaccess.dto.UpdateCourseVisibilityRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform console → Course visibility (spec §2.5). SUPER_ADMIN only; there is
 * no org-scoped door to this surface at all, which is the point — visibility is
 * the platform's word about the catalog and organizations only inherit.
 */
@RestController
@RequestMapping(path = "/api/admin/course-visibility", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Course visibility (platform)", description = "Which organizations can see and assign each course.")
public class CourseVisibilityController {

    private final CourseVisibilityService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public List<CourseVisibilityView> list() {
        return service.list();
    }

    /** Options for the explicit-org-list picker. */
    @GetMapping("/organizations")
    public List<OrgOptionView> organizations() {
        return service.organizations();
    }

    /** Tier names in capacity order, for the minimum-tier picker. */
    @GetMapping("/tiers")
    public List<String> tiers() {
        return service.tiers();
    }

    @PutMapping("/{courseId}")
    public ResponseEntity<Void> update(@PathVariable UUID courseId,
                                       @Valid @RequestBody UpdateCourseVisibilityRequest request) {
        service.update(courseId, request, currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }
}
