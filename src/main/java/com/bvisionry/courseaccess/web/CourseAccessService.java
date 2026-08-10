package com.bvisionry.courseaccess.web;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.courseaccess.domain.EffectiveCourse;
import com.bvisionry.courseaccess.domain.EffectiveCourseStatus;
import com.bvisionry.courseaccess.domain.EffectiveCourses;
import com.bvisionry.courseaccess.dto.CatalogCourseView;
import com.bvisionry.courseaccess.dto.MemberCourseView;
import com.bvisionry.courseaccess.dto.MyLibraryResponse;
import com.bvisionry.courseaccess.repository.CourseAccessReadRepository;
import com.bvisionry.courseaccess.repository.CourseAssignmentWriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one read model of spec §3, and the one write that turns a read-time claim
 * into a real enrollment.
 *
 * <p><strong>Who uses {@link #effectiveCoursesOf}:</strong> the member Library,
 * the journey's direct-assignment and right-rail sections, the founder-profile
 * Work tab and the org Courses tab's per-member view. One merge, one precedence
 * rule, so no two surfaces can disagree about why someone has a course.
 *
 * <p><strong>Lazy materialization.</strong> An org rule and an AI suggestion are
 * claims, not rows — the {@code enrollment} row appears on first open (the D1
 * {@code ensureEnrollment} pattern) or on Accept. Until then the read model
 * UNIONS the claims with the real rows, which is what makes "new members are
 * covered automatically" true without a backfill, and what makes progress and
 * completion reads (which only ever see real rows) correct by construction: a
 * member who has never opened a course has 0% either way.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseAccessService {

    private final CourseAccessReadRepository reads;
    private final CourseAssignmentWriteRepository writes;
    private final CourseVisibilityAccess visibility;

    /** Every course this member effectively has, deduped, strongest source shown. */
    @Transactional(readOnly = true)
    public List<EffectiveCourse> effectiveCoursesOf(UUID userId) {
        return effectiveCoursesOf(userId, reads.orgIdOf(userId));
    }

    @Transactional(readOnly = true)
    public List<EffectiveCourse> effectiveCoursesOf(UUID userId, UUID orgId) {
        var enrolments = reads.enrolments(userId);
        var rules = reads.rules(orgId, userId);
        var suggestions = reads.suggestions(userId);

        Set<UUID> ids = new LinkedHashSet<>();
        enrolments.forEach(r -> ids.add(r.courseId()));
        rules.forEach(r -> ids.add(r.courseId()));
        suggestions.forEach(r -> ids.add(r.courseId()));

        return EffectiveCourses.merge(enrolments, rules, suggestions, reads.courseMeta(ids));
    }

    /** The member Library (spec §2.1): what they have plus the visible catalog. */
    @Transactional(readOnly = true)
    public MyLibraryResponse library(UUID userId, UUID orgId) {
        Instant now = Instant.now();
        List<MemberCourseView> courses = effectiveCoursesOf(userId, orgId).stream()
                .map(c -> toView(c, now))
                .toList();
        List<CatalogCourseView> catalog = reads.visibleCatalog(orgId).stream()
                .map(r -> new CatalogCourseView(r.courseId(), r.title(), r.slug(),
                        r.category(), r.level(), r.lessonsCount()))
                .toList();
        return new MyLibraryResponse(courses, catalog);
    }

    public static MemberCourseView toView(EffectiveCourse c, Instant now) {
        return new MemberCourseView(c.courseId(), c.courseTitle(), c.courseSlug(),
                c.source(), c.status(), c.required(), c.deadline(), c.overdue(now),
                c.progressPct(), c.assignedAt(), c.completedAt(), c.assignedByName(),
                c.reason(), c.materialized());
    }

    /**
     * One-tap Accept (spec §2.1, §7b) — and the same call materializes an org
     * rule the member is opening for the first time.
     *
     * <p>Both are "turn the claim I already have into a seat": the member is not
     * choosing a course, they are taking one the platform or their admin already
     * put in front of them. Anything they do NOT already have effectively is
     * refused here — self-enrolment has its own endpoint, and letting this one
     * mint enrollments would let a member set their own {@code required} flag.
     */
    @Transactional
    public MemberCourseView accept(UUID userId, UUID orgId, UUID courseId) {
        EffectiveCourse claim = effectiveCoursesOf(userId, orgId).stream()
                .filter(c -> c.courseId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "This course has not been assigned or suggested to you"));

        // PUBLISHED *and* visible. Suggest mode deliberately does not check the
        // course's state at decision time (refusing to suggest a course that
        // publishes tomorrow would lose the reason for good — nothing re-drives
        // an evaluation), so this is the check, and it is the same one the org
        // admin's assign path uses. Downgrade policy (spec §3) rides on it: keep
        // progress, block NEW content.
        if (!visibility.isAssignable(courseId, orgId)) {
            throw new BadRequestException("This course is not available to your organization");
        }

        writes.upsert(userId, courseId, claim.source(), claim.required(), claim.deadline(), null);
        if (claim.status() == EffectiveCourseStatus.SUGGESTED) {
            writes.acceptSuggestion(userId, courseId, Instant.now());
        }
        log.info("Member {} accepted course {} (source {})", userId, courseId, claim.source());

        Instant now = Instant.now();
        return effectiveCoursesOf(userId, orgId).stream()
                .filter(c -> c.courseId().equals(courseId))
                .findFirst()
                .map(c -> toView(c, now))
                .orElseThrow(() -> new BadRequestException("Enrollment could not be created"));
    }
}
