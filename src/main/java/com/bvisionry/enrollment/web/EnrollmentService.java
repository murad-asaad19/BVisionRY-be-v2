package com.bvisionry.enrollment.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
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
    private final CourseVisibilityAccess courseVisibility;
    private final CurrentUserAccessor currentUser;

    public EnrollmentService(EnrollmentRepository enrollments,
                             ContentProgressRepository progresses,
                             CourseRepository courses,
                             SectionRepository sections,
                             ContentRepository contents,
                             MediaService mediaService,
                             CertificateService certificateService,
                             UserRepository users,
                             CourseVisibilityAccess courseVisibility,
                             CurrentUserAccessor currentUser) {
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.courses = courses;
        this.sections = sections;
        this.contents = contents;
        this.mediaService = mediaService;
        this.certificateService = certificateService;
        this.users = users;
        this.courseVisibility = courseVisibility;
        this.currentUser = currentUser;
    }

    // -------------------------------------------------------------------------
    // Enroll
    // -------------------------------------------------------------------------

    /**
     * Creates or returns the existing enrollment for the current user and the
     * course identified by {@code slug}.
     *
     * <p>Uses {@code findAny...} — the one read here that still sees an
     * admin-removed (CANCELLED) row — for two reasons. The mechanical one: the row
     * keeps its slot in {@code uq_enrollment_user_course}, so the filtered read
     * would report "not enrolled" and send this straight into a constraint
     * violation. The deliberate one: {@link #reactivateIfRemoved} then hands the
     * founder their course back with every lesson they had completed still ticked.
     */
    @Transactional
    public EnrollmentDto enroll(String slug) {
        UUID userId = currentUser.require().userId();
        var course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        // Spec §3 visibility: an org's members may only take up what the platform
        // has made visible to that org. Enforced HERE rather than on the catalog
        // list because this is the act that creates a seat — a filtered list an
        // attacker can skip past is not a control. Org-less users (super admins)
        // are not gated; see CourseVisibilityAccess#isVisibleToUser.
        // One getId() call, reused: the ArchUnit ratchet counts each cross-feature
        // call SITE, so a second one is a new frozen violation for an edge that
        // already exists.
        UUID enrolledCourseId = course.getId();
        if (!courseVisibility.isVisibleToUser(userId, enrolledCourseId)) {
            throw new NotEnrolledException(slug);
        }

        // An admin removed this member from this course (spec §2.4 exclusion /
        // V157 override). REFUSED, not silently re-granted — see the note on
        // reactivateIfRemoved below for why this reverses the pre-§3 reading.
        if (enrollments.isRemovedByAdmin(userId, enrolledCourseId)) {
            throw new BadRequestException(
                    "Your organization removed you from this course. Ask your admin to add it back.");
        }

        return enrollments.findAnyByUserIdAndCourseId(userId, enrolledCourseId)
                .map(e -> live(toDto(reactivateIfRemoved(e), course), e))
                .orElseGet(() -> createEnrollment(userId, course));
    }

    /**
     * A founder self-enrolling in a course an admin removed them from gets it back,
     * progress intact.
     *
     * <p><strong>An overridden course no longer reaches here at all.</strong>
     * The override used to be read narrowly — "an admin overrides the ENGINE's
     * automatic decision, not a public catalog" — and self-enrolment was allowed
     * to restore access. Spec §3 makes the override the member-level EXCLUSION
     * that beats an org-level rule, which only means anything if the member
     * cannot undo it: an admin who removes someone from a required course would
     * otherwise be one click from being overruled. {@link #enroll} now refuses
     * before it gets here, so this method's remaining job is the CANCELLED rows
     * nothing excludes any more — remove-for-everyone stamps an override per
     * affected member, but those {@code scope = 'ORG'} rows are deleted again by
     * the next org-wide assign (V184), leaving the cancelled row free to revive.
     *
     * <p>The restored status is derived rather than assumed ACTIVE: removal only
     * ever changed {@code status}, so a founder who had FINISHED the course comes
     * back COMPLETED, with their certificate still valid.
     */
    private Enrollment reactivateIfRemoved(Enrollment enrollment) {
        if (enrollment.getStatus() != EnrollmentStatus.CANCELLED) {
            return enrollment;
        }
        enrollment.setStatus(enrollment.getCompletedAt() != null
                ? EnrollmentStatus.COMPLETED
                : EnrollmentStatus.ACTIVE);
        return enrollments.save(enrollment);
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
                    .map(existing -> live(toDto(existing, course), existing))
                    .orElseThrow(() -> ex);
        }
    }

    // -------------------------------------------------------------------------
    // My enrollments
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EnrollmentDto> myEnrollments() {
        UUID userId = currentUser.require().userId();
        List<Enrollment> list = enrollments.findByUserId(userId);
        // Batch-load the courses (incl. DRAFT/ARCHIVED) so each DTO carries its
        // title/slug — the catalog endpoint is published-only, so client joins
        // against it silently drop courses the learner is enrolled in.
        Map<UUID, Course> byId = courses.findAllById(
                        list.stream().map(Enrollment::getCourseId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Course::getId, c -> c));
        Map<UUID, Integer> pct = livePct(list);
        return list.stream()
                .map(e -> live(toDto(e, byId.get(e.getCourseId())), e, pct))
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
        UUID userId = currentUser.require().userId();
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
                live(toDto(enrollment, course), enrollment),
                sectionViews);
    }

    // -------------------------------------------------------------------------
    // Mark complete
    // -------------------------------------------------------------------------

    /**
     * Binds {@code contentId} to the enrollment's course (content → section →
     * course). Shared by every player write that takes a content id alongside an
     * enrollment id — completion here, quiz attempts, playback positions — so the
     * rule lives once, where all three route through, instead of in each caller.
     *
     * <p>Without it a caller who owns enrollment A can write rows against content
     * belonging to course B: forging 100% (and a certificate) on the completion
     * path, and polluting attempt/progress data on the others.
     *
     * <p>Public because the other two callers live outside this class;
     * {@code EnrollmentService} is where it belongs regardless, since it already
     * owns both the enrollment and the catalog lookup.
     */
    public void requireContentInCourse(UUID contentId, Enrollment enrollment) {
        Content content = contents.findByIdWithSectionAndCourse(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content", contentId.toString()));
        if (!content.getSection().getCourse().getId().equals(enrollment.getCourseId())) {
            throw new com.bvisionry.common.exception.BadRequestException(
                    "Content does not belong to this enrollment's course");
        }
    }

    /**
     * Marks a content item as complete and recomputes {@code progress_pct} on the
     * enrollment.
     *
     * @return updated per-lesson progress record.
     */
    @Transactional
    public ContentProgressDto markComplete(UUID enrollmentId, UUID contentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment", enrollmentId.toString()));

        // Ownership check
        UUID userId = currentUser.require().userId();
        if (!enrollment.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not your enrollment");
        }

        // Removal check. This is the one enrolment read in the codebase that is
        // BY ID rather than by (user, course), so the repository's status filter
        // cannot cover it — and a player tab already open when an admin removed
        // the founder still holds a perfectly valid enrollment id. Without this,
        // that tab could keep completing lessons, drive progress to 100% and
        // mint a certificate for a course they are no longer on.
        if (enrollment.getStatus() == EnrollmentStatus.CANCELLED) {
            throw new NotEnrolledException(enrollment.getCourseId().toString());
        }

        requireContentInCourse(contentId, enrollment);

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
                .orElseThrow(() -> new ResourceNotFoundException("Content", contentId.toString()));

        // Content must belong to this course (via section → course)
        if (!content.getSection().getCourse().getId().equals(course.getId())) {
            throw new BadRequestException("Content does not belong to course: " + slug);
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
        UUID viewerId = currentUser.require().userId();
        UUID openCourseId = course.getId();
        boolean enrolled = enrollments.existsByUserIdAndCourseId(viewerId, openCourseId);
        boolean previewable = content.isAllowPreview() && course.getState() == CourseState.PUBLISHED;
        if (!enrolled && !previewable) {
            throw new NotEnrolledException(slug);
        }

        // Spec §3 downgrade policy: "keep progress, block new content, never
        // delete data". A course the caller's org can no longer see keeps its
        // enrollment, its progress bar, its certificate and its place in every
        // report — but no further lesson BODY opens. Enforced at content-open
        // rather than at enrollment because that is the smallest honest reading
        // of "block new content"; the learn view still renders the outline.
        if (!courseVisibility.isVisibleToUser(viewerId, openCourseId)) {
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

    /**
     * The same DTO with progress counted against the course's CURRENT lessons
     * instead of the {@code progress_pct} column.
     *
     * <p>That column is a cache only the learner's own completions ever write, and
     * its denominator lives in another slice: an author who adds, deletes or
     * replaces lessons moves the lesson count (and cascade-deletes the
     * {@code content_progress} rows pointing at the old ones) without any write
     * touching the enrolment, so every enrolment keeps reporting the percentage
     * that was true at the last click. Derived here rather than repaired on the
     * authoring path because no schedule of "recompute on lesson change" survives
     * the next mutation someone forgets. Shared with the five raw-SQL reads in
     * other slices via {@link com.bvisionry.common.progress.CourseProgressSql}.
     *
     * <p>A patch on the record rather than a parameter on {@code toDto}: the
     * ArchUnit ratchet freezes the exact signature that may name a
     * {@code catalog.Course}, and a new one is a new frozen violation.
     */
    private EnrollmentDto live(EnrollmentDto dto, Enrollment e, Map<UUID, Integer> livePct) {
        return new EnrollmentDto(dto.id(), dto.courseId(), dto.courseTitle(), dto.courseSlug(),
                dto.status(), livePct.getOrDefault(e.getId(), e.getProgressPct()),
                dto.enrolledAt(), dto.completedAt());
    }

    private EnrollmentDto live(EnrollmentDto dto, Enrollment e) {
        return live(dto, e, livePct(List.of(e)));
    }

    /** See {@link #live}. Falls back to the stored value for any row not returned. */
    private Map<UUID, Integer> livePct(List<Enrollment> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return enrollments.livePct(list.stream().map(Enrollment::getId).toList())
                .stream()
                .collect(Collectors.toMap(EnrollmentRepository.LivePct::getId,
                        EnrollmentRepository.LivePct::getPct));
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
