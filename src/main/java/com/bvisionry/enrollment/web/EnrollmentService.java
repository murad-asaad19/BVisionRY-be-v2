package com.bvisionry.enrollment.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.CourseState;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.certificate.service.CertificateService;
import com.bvisionry.media.MediaService;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.SectionRepository;
import com.bvisionry.catalog.web.CourseNotFoundException;
import com.bvisionry.enrollment.domain.ContentProgress;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.domain.EnrollmentStatus;
import com.bvisionry.enrollment.dto.ContentProgressDto;
import com.bvisionry.enrollment.dto.EnrollmentDto;
import com.bvisionry.enrollment.dto.LearnViewDto;
import com.bvisionry.enrollment.dto.LessonContentDto;
import com.bvisionry.enrollment.repository.ContentProgressRepository;
import com.bvisionry.enrollment.repository.EnrollmentRepository;

/**
 * Application service for enrollment, progress, and the player learn view.
 */
@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final ContentProgressRepository progresses;
    private final CourseRepository courses;
    private final SectionRepository sections;
    private final ContentRepository contents;
    private final MediaService mediaService;
    private final CertificateService certificateService;
    private final UserRepository users;

    public EnrollmentService(EnrollmentRepository enrollments,
                             ContentProgressRepository progresses,
                             CourseRepository courses,
                             SectionRepository sections,
                             ContentRepository contents,
                             MediaService mediaService,
                             CertificateService certificateService,
                             UserRepository users) {
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.courses = courses;
        this.sections = sections;
        this.contents = contents;
        this.mediaService = mediaService;
        this.certificateService = certificateService;
        this.users = users;
    }

    // -------------------------------------------------------------------------
    // Enroll
    // -------------------------------------------------------------------------

    /**
     * Creates or returns the existing enrollment for the current user and the
     * course identified by {@code slug}.
     */
    @Transactional
    public EnrollmentDto enroll(String slug) {
        UUID userId = SecurityUtils.getCurrentUserId();
        var course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        return enrollments.findByUserIdAndCourseId(userId, course.getId())
                .map(e -> toDto(e, course))
                .orElseGet(() -> createEnrollment(userId, course));
    }

    /**
     * Inserts a fresh enrollment, tolerating the {@code uq_enrollment_user_course}
     * unique constraint: two concurrent enroll calls both miss the find above and
     * race to insert. The loser catches the violation and re-reads the winner's row
     * so the endpoint stays idempotent (returns the existing enrollment) instead of
     * surfacing an HTTP 500. Mirrors the create-or-return pattern in
     * {@link com.bvisionry.certificate.service.CertificateService}.
     */
    private EnrollmentDto createEnrollment(UUID userId, Course course) {
        Enrollment e = new Enrollment();
        e.setUserId(userId);
        e.setCourseId(course.getId());
        try {
            return toDto(enrollments.saveAndFlush(e), course);
        } catch (DataIntegrityViolationException ex) {
            return enrollments.findByUserIdAndCourseId(userId, course.getId())
                    .map(existing -> toDto(existing, course))
                    .orElseThrow(() -> ex);
        }
    }

    // -------------------------------------------------------------------------
    // My enrollments
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EnrollmentDto> myEnrollments() {
        UUID userId = SecurityUtils.getCurrentUserId();
        List<Enrollment> list = enrollments.findByUserId(userId);
        // Batch-load the courses (incl. DRAFT/ARCHIVED) so each DTO carries its
        // title/slug — the catalog endpoint is published-only, so client joins
        // against it silently drop courses the learner is enrolled in.
        Map<UUID, Course> byId = courses.findAllById(
                        list.stream().map(Enrollment::getCourseId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Course::getId, c -> c));
        return list.stream()
                .map(e -> toDto(e, byId.get(e.getCourseId())))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Learn view
    // -------------------------------------------------------------------------

    /**
     * Returns the course curriculum annotated with per-lesson completion state
     * for the currently enrolled viewer.
     */
    @Transactional(readOnly = true)
    public LearnViewDto learnView(String slug) {
        UUID userId = SecurityUtils.getCurrentUserId();
        var course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        Enrollment enrollment = enrollments.findByUserIdAndCourseId(userId, course.getId())
                .orElseThrow(() -> new NotEnrolledException(slug));

        List<ContentProgress> cpList = progresses.findByEnrollmentId(enrollment.getId());
        Set<UUID> completed = cpList.stream()
                .filter(ContentProgress::isCompleted)
                .map(ContentProgress::getContentId)
                .collect(Collectors.toSet());

        List<LearnViewDto.SectionView> sectionViews = sections.findByCourseIdWithContents(course.getId())
                .stream()
                .map(s -> toSectionView(s, completed))
                .toList();

        return new LearnViewDto(
                course.getId().toString(),
                course.getSlug(),
                course.getTitle(),
                toDto(enrollment, course),
                sectionViews);
    }

    // -------------------------------------------------------------------------
    // Mark complete
    // -------------------------------------------------------------------------

    /**
     * Marks a content item as complete and recomputes {@code progress_pct} on the
     * enrollment.
     *
     * @return updated per-lesson progress record.
     */
    @Transactional
    public ContentProgressDto markComplete(UUID enrollmentId, UUID contentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found: " + enrollmentId));

        // Ownership check
        UUID userId = SecurityUtils.getCurrentUserId();
        if (!enrollment.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not your enrollment");
        }

        // Cross-course guard: completing a content item recomputes progress_pct and can
        // auto-issue a certificate. Without binding the content to THIS enrollment's
        // course, a caller could forge 100% (and a certificate) by marking lessons that
        // belong to another course complete. Verify the content lives under the
        // enrollment's course (content → section → course) before persisting progress.
        Content content = contents.findByIdWithSectionAndCourse(contentId)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));
        if (!content.getSection().getCourse().getId().equals(enrollment.getCourseId())) {
            throw new com.bvisionry.common.exception.BadRequestException(
                    "Content does not belong to this enrollment's course");
        }

        ContentProgress cp = progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)
                .orElseGet(() -> {
                    ContentProgress fresh = new ContentProgress();
                    fresh.setEnrollment(enrollment);
                    fresh.setContentId(contentId);
                    return fresh;
                });

        cp.setCompleted(true);
        cp.setCompletedAt(OffsetDateTime.now());
        progresses.save(cp);

        recomputeProgress(enrollment);

        return new ContentProgressDto(
                contentId.toString(),
                cp.isCompleted(),
                cp.getCompletedAt());
    }

    // -------------------------------------------------------------------------
    // Lesson content (body + media)
    // -------------------------------------------------------------------------

    /**
     * Returns the full lesson payload (body + media URLs) for a content item.
     *
     * <p>Courses are a public, cross-org catalog, so this learner-facing read is
     * gated by enrollment, not org membership. The body is served only to learners
     * enrolled in the course, or for preview-enabled content on a PUBLISHED course;
     * DRAFT/ARCHIVED bodies are never returned to non-enrolled callers.
     */
    @Transactional(readOnly = true)
    public LessonContentDto lessonContent(String slug, UUID contentId) {
        var course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        var content = contents.findByIdWithSectionAndCourse(contentId)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));

        // Content must belong to this course (via section → course)
        if (!content.getSection().getCourse().getId().equals(course.getId())) {
            throw new IllegalArgumentException("Content does not belong to course: " + slug);
        }

        // Access control: this endpoint returns the full lesson body and freshly
        // presigned media URLs, and findBySlug loads courses in ANY state (incl.
        // DRAFT/ARCHIVED). Courses are a PUBLIC, cross-org catalog: a member of one
        // org can legitimately enroll in and learn a course owned by another org, so
        // this learner-facing read must NOT be gated by org membership. Gate it by
        // ENROLLMENT instead so non-owners can never pull unpublished bodies or paid
        // media for free: allow the body only if the caller is enrolled in the course,
        // OR the content is explicitly preview-enabled on a PUBLISHED course. The
        // allowPreview flag is HONORED (gates access), not merely echoed. An unenrolled
        // user of ANY org still cannot read locked content.
        boolean enrolled = enrollments.existsByUserIdAndCourseId(
                SecurityUtils.getCurrentUserId(), course.getId());
        boolean previewable = content.isAllowPreview() && course.getState() == CourseState.PUBLISHED;
        if (!enrolled && !previewable) {
            throw new NotEnrolledException(slug);
        }

        // Resolve minio:// markers to fresh presigned GET URLs; external/HLS URLs pass through.
        return new LessonContentDto(
                content.getId().toString(),
                content.getTitle(),
                content.getContentType().name(),
                content.getBody(),
                mediaService.resolveUrl(content.getVideoUrl()),
                mediaService.resolveUrl(content.getAssetUrl()),
                content.getDurationMin() == null ? 0 : content.getDurationMin(),
                content.isAllowPreview());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private EnrollmentDto toDto(Enrollment e, Course course) {
        return new EnrollmentDto(
                e.getId().toString(),
                e.getCourseId().toString(),
                course == null ? null : course.getTitle(),
                course == null ? null : course.getSlug(),
                e.getStatus().name(),
                e.getProgressPct(),
                e.getEnrolledAt(),
                e.getCompletedAt());
    }

    private LearnViewDto.SectionView toSectionView(Section s, Set<UUID> completed) {
        List<LearnViewDto.LessonView> lessons = s.getContents().stream()
                .map(c -> new LearnViewDto.LessonView(
                        c.getId().toString(),
                        c.getTitle(),
                        c.getContentType().name(),
                        c.getDurationMin() == null ? 0 : c.getDurationMin(),
                        c.isAllowPreview(),
                        completed.contains(c.getId())))
                .toList();
        return new LearnViewDto.SectionView(
                s.getId().toString(),
                s.getTitle(),
                s.getSequence(),
                lessons);
    }

    private void recomputeProgress(Enrollment enrollment) {
        // Count total lessons in the course
        long total = sections.findByCourseIdWithContents(enrollment.getCourseId())
                .stream()
                .mapToLong(s -> s.getContents().size())
                .sum();

        if (total == 0) {
            return;
        }

        long done = progresses.countByEnrollmentIdAndCompletedTrue(enrollment.getId());
        int pct = (int) Math.round((done * 100.0) / total);
        enrollment.setProgressPct(pct);

        if (pct >= 100) {
            enrollment.setStatus(EnrollmentStatus.COMPLETED);
            enrollment.setCompletedAt(OffsetDateTime.now());
            enrollments.save(enrollment);
            issueCertificateQuietly(enrollment);
            return;
        }

        enrollments.save(enrollment);
    }

    /**
     * Issues a completion certificate when an enrollment reaches 100%. Idempotent
     * (find-or-create by enrollment) and best-effort: a certificate failure must
     * never block course completion.
     */
    private void issueCertificateQuietly(Enrollment enrollment) {
        try {
            Course course = courses.findById(enrollment.getCourseId()).orElse(null);
            if (course == null) {
                return;
            }
            // Snapshot the learner from the enrollment's OWNER, not the current
            // principal. The completion path is learner-driven today, but loading by
            // enrollment.getUserId() keeps the certificate name correct on any future
            // admin/impersonation/async path where the caller != the enrolled user.
            User learner = users.findById(enrollment.getUserId()).orElse(null);
            if (learner == null) {
                return;
            }
            certificateService.issue(enrollment, course, learner);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(EnrollmentService.class)
                    .warn("Certificate issuance skipped for enrollment {}: {}",
                            enrollment.getId(), ex.toString());
        }
    }
}
