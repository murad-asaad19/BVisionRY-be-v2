package com.bvisionry.cohortview.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.cohortview.dto.CohortOutlineResponse;
import com.bvisionry.cohortview.dto.CohortOverviewResponse;
import com.bvisionry.cohortview.dto.CohortRosterResponse;
import com.bvisionry.cohortview.dto.CourseProgressDetailResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The dedicated cohort view (redesign spec §13.7) — the admin's overview and
 * roster for one cohort, plus the per-member course-progress drill-down the
 * roster links into. Same guard stack as the other org-scoped consoles:
 * {@code OrgAccessInterceptor} on {@code /api/organizations/**} plus the class
 * {@code @PreAuthorize}; the service re-anchors on the org-scoped row, so a
 * cohort this org does not run is a 404.
 *
 * <p>Participation and growth summaries are NOT here — they already have
 * endpoints ({@code …/participation}, {@code …/comparisons}) and the screen
 * composes them.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Cohort view (admin)", description = "Overview, roster and course progress for one cohort.")
public class CohortViewController {

    private final CohortViewService service;

    @GetMapping("/cohorts/{cohortId}/overview")
    public CohortOverviewResponse overview(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return service.overview(orgId, cohortId);
    }

    @GetMapping("/cohorts/{cohortId}/outline")
    public CohortOutlineResponse outline(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return service.outline(orgId, cohortId);
    }

    @GetMapping("/cohorts/{cohortId}/roster")
    public CohortRosterResponse roster(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return service.roster(orgId, cohortId);
    }

    @GetMapping("/members/{memberId}/courses/{courseId}/progress")
    public CourseProgressDetailResponse courseProgress(@PathVariable UUID orgId,
            @PathVariable UUID memberId, @PathVariable UUID courseId) {
        return service.courseProgress(orgId, memberId, courseId);
    }
}
