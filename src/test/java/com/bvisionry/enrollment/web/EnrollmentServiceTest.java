package com.bvisionry.enrollment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.ContentType;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.CourseState;
import com.bvisionry.catalog.domain.EnrollPolicy;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.SectionRepository;
import com.bvisionry.catalog.web.CourseNotFoundException;
import com.bvisionry.certificate.service.CertificateService;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.enrollment.domain.ContentProgress;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.domain.EnrollmentStatus;
import com.bvisionry.enrollment.dto.ContentProgressDto;
import com.bvisionry.enrollment.dto.EnrollmentDto;
import com.bvisionry.enrollment.dto.LearnViewDto;
import com.bvisionry.enrollment.dto.LessonContentDto;
import com.bvisionry.enrollment.repository.ContentProgressRepository;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.media.MediaService;

/**
 * Unit tests for {@link EnrollmentService}. Mirrors the Mockito style of
 * {@code EvaluationServiceTest} / {@code QuizServiceTest}: constructor injection
 * via {@link InjectMocks}, {@link ArgumentCaptor} on persisted entities, and
 * specific state assertions.
 *
 * <p>{@code SecurityUtils} reads the caller from the {@link SecurityContextHolder};
 * rather than static-mocking, each test runs with a real {@link User} principal
 * installed in the context (the shape {@code JwtAuthenticationFilter} produces),
 * so {@code getCurrentUserId()} resolves naturally.
 */
@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    @Mock private EnrollmentRepository enrollments;
    @Mock private ContentProgressRepository progresses;
    @Mock private CourseRepository courses;
    @Mock private SectionRepository sections;
    @Mock private ContentRepository contents;
    @Mock private MediaService mediaService;
    @Mock private CertificateService certificateService;
    @Mock private UserRepository users;

    @InjectMocks
    private EnrollmentService service;

    private static final String SLUG = "java-101";

    private UUID currentUserId;
    private User currentUser;
    private UUID courseId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        courseId = UUID.randomUUID();

        currentUser = new User();
        currentUser.setId(currentUserId);
        currentUser.setEmail("learner@test.com");
        currentUser.setName("Learner");
        currentUser.setRole(UserRole.ORG_ADMIN);
        currentUser.setStatus(UserStatus.ACTIVE);

        authenticate(currentUser);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(User user) {
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    // -------------------------------------------------------------------------
    // Builders
    // -------------------------------------------------------------------------

    private Course course(UUID id, String slug, String title) {
        Course c = new Course();
        c.setId(id);
        c.setSlug(slug);
        c.setTitle(title);
        return c;
    }

    private Enrollment enrollment(UUID id, UUID userId, UUID courseId) {
        Enrollment e = new Enrollment();
        e.setId(id);
        e.setUserId(userId);
        e.setCourseId(courseId);
        return e;
    }

    /** A section carrying {@code lessons} placeholder contents (only size is read by recompute). */
    private Section sectionWith(int lessons) {
        Section s = new Section();
        s.setId(UUID.randomUUID());
        List<Content> list = new ArrayList<>();
        for (int i = 0; i < lessons; i++) {
            list.add(new Content());
        }
        s.setContents(list);
        return s;
    }

    /** A content whose section→course chain resolves to {@code ownerCourseId}. */
    private Content contentUnderCourse(UUID contentId, UUID ownerCourseId) {
        Course c = new Course();
        c.setId(ownerCourseId);
        c.setState(CourseState.PUBLISHED);
        Section s = new Section();
        s.setId(UUID.randomUUID());
        s.setCourse(c);
        Content ct = new Content();
        ct.setId(contentId);
        ct.setSection(s);
        ct.setTitle("Lesson 1");
        ct.setContentType(ContentType.VIDEO);
        return ct;
    }

    // =========================================================================
    // enroll
    // =========================================================================

    @Test
    void enroll_newLearner_createsActiveEnrollment() {
        Course course = course(courseId, SLUG, "Java 101");
        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId)).thenReturn(Optional.empty());
        when(enrollments.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> {
            Enrollment e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        EnrollmentDto dto = service.enroll(SLUG);

        assertThat(dto.id()).isNotNull();
        assertThat(dto.courseId()).isEqualTo(courseId.toString());
        assertThat(dto.courseTitle()).isEqualTo("Java 101");
        assertThat(dto.courseSlug()).isEqualTo(SLUG);
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.progressPct()).isZero();

        ArgumentCaptor<Enrollment> captor = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollments).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(currentUserId);
        assertThat(captor.getValue().getCourseId()).isEqualTo(courseId);
    }

    @Test
    void enroll_alreadyEnrolled_returnsExistingWithoutInsert() {
        Course course = course(courseId, SLUG, "Java 101");
        Enrollment existing = enrollment(UUID.randomUUID(), currentUserId, courseId);
        existing.setProgressPct(42);
        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId)).thenReturn(Optional.of(existing));

        EnrollmentDto dto = service.enroll(SLUG);

        assertThat(dto.id()).isEqualTo(existing.getId().toString());
        assertThat(dto.progressPct()).isEqualTo(42);
        // Idempotent: an existing enrollment is never re-inserted.
        verify(enrollments, never()).saveAndFlush(any());
    }

    @Test
    void enroll_uniqueConstraintRace_reReadsWinnerRowInsteadOf500() {
        // Two concurrent enrolls both miss the initial find and race to INSERT; the
        // loser catches uq_enrollment_user_course and re-reads the winner's row so the
        // endpoint stays idempotent rather than surfacing an HTTP 500.
        Course course = course(courseId, SLUG, "Java 101");
        Enrollment winner = enrollment(UUID.randomUUID(), currentUserId, courseId);
        winner.setProgressPct(7);
        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId))
                .thenReturn(Optional.empty())          // initial miss
                .thenReturn(Optional.of(winner));       // re-read after the violation
        when(enrollments.saveAndFlush(any(Enrollment.class)))
                .thenThrow(new DataIntegrityViolationException("uq_enrollment_user_course"));

        EnrollmentDto dto = service.enroll(SLUG);

        assertThat(dto.id()).isEqualTo(winner.getId().toString());
        assertThat(dto.progressPct()).isEqualTo(7);
        verify(enrollments).saveAndFlush(any());
    }

    @Test
    void enroll_unknownSlug_throwsCourseNotFound() {
        when(courses.findBySlug(SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enroll(SLUG))
                .isInstanceOf(CourseNotFoundException.class);

        verifyNoInteractions(enrollments);
    }

    @Test
    void enroll_ignoresEnrollPolicyAndDraftState_currentBehavior() {
        // CURRENT behavior (flagged in the 2026-06-26 review): enroll() looks the
        // course up with the plain findBySlug (any state) and never consults
        // enrollPolicy or state. A DRAFT, invitation-only course still enrolls freely.
        Course gated = course(courseId, SLUG, "Invite Only (Draft)");
        gated.setEnrollPolicy(EnrollPolicy.INVITATION);
        gated.setState(CourseState.DRAFT);
        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(gated));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId)).thenReturn(Optional.empty());
        when(enrollments.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> {
            Enrollment e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        EnrollmentDto dto = service.enroll(SLUG);

        assertThat(dto.status()).isEqualTo("ACTIVE");
        verify(enrollments).saveAndFlush(any());
    }

    // =========================================================================
    // myEnrollments
    // =========================================================================

    @Test
    void myEnrollments_batchLoadsCourses_includingNonPublishedones() {
        UUID courseAId = UUID.randomUUID();
        UUID courseBId = UUID.randomUUID();
        Enrollment eA = enrollment(UUID.randomUUID(), currentUserId, courseAId);
        Enrollment eB = enrollment(UUID.randomUUID(), currentUserId, courseBId);

        Course courseA = course(courseAId, "a", "Course A");
        Course courseB = course(courseBId, "b", "Course B (Draft)");
        courseB.setState(CourseState.DRAFT);

        when(enrollments.findByUserId(currentUserId)).thenReturn(List.of(eA, eB));
        when(courses.findAllById(any())).thenReturn(List.of(courseA, courseB));

        List<EnrollmentDto> result = service.myEnrollments();

        assertThat(result).hasSize(2);
        // A DRAFT course the learner is enrolled in must still carry its title/slug
        // (the public catalog endpoint is published-only and would drop it).
        assertThat(result).anySatisfy(d -> {
            assertThat(d.courseId()).isEqualTo(courseBId.toString());
            assertThat(d.courseTitle()).isEqualTo("Course B (Draft)");
        });
    }

    @Test
    void myEnrollments_courseDeleted_returnsDtoWithNullTitle() {
        UUID courseAId = UUID.randomUUID();
        Enrollment eA = enrollment(UUID.randomUUID(), currentUserId, courseAId);
        when(enrollments.findByUserId(currentUserId)).thenReturn(List.of(eA));
        // Batch load misses the course entirely (deleted/inaccessible).
        when(courses.findAllById(any())).thenReturn(List.of());

        List<EnrollmentDto> result = service.myEnrollments();

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.courseId()).isEqualTo(courseAId.toString());
            assertThat(d.courseTitle()).isNull();
            assertThat(d.courseSlug()).isNull();
        });
    }

    // =========================================================================
    // learnView
    // =========================================================================

    @Test
    void learnView_annotatesLessonsWithCompletionState() {
        Course course = course(courseId, SLUG, "Java 101");
        Enrollment enr = enrollment(UUID.randomUUID(), currentUserId, courseId);

        UUID doneContentId = UUID.randomUUID();
        UUID todoContentId = UUID.randomUUID();

        ContentProgress donCp = new ContentProgress();
        donCp.setContentId(doneContentId);
        donCp.setCompleted(true);
        ContentProgress todoCp = new ContentProgress();
        todoCp.setContentId(todoContentId);
        todoCp.setCompleted(false);

        Section section = new Section();
        section.setId(UUID.randomUUID());
        section.setTitle("Basics");
        section.setSequence(1);
        Content doneContent = new Content();
        doneContent.setId(doneContentId);
        doneContent.setTitle("Lesson A");
        doneContent.setContentType(ContentType.VIDEO);
        Content todoContent = new Content();
        todoContent.setId(todoContentId);
        todoContent.setTitle("Lesson B");
        todoContent.setContentType(ContentType.VIDEO);
        section.setContents(List.of(doneContent, todoContent));

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId)).thenReturn(Optional.of(enr));
        when(progresses.findByEnrollmentId(enr.getId())).thenReturn(List.of(donCp, todoCp));
        when(sections.findByCourseIdWithContents(courseId)).thenReturn(List.of(section));

        LearnViewDto view = service.learnView(SLUG);

        assertThat(view.slug()).isEqualTo(SLUG);
        assertThat(view.title()).isEqualTo("Java 101");
        assertThat(view.sections()).singleElement().satisfies(sv -> {
            assertThat(sv.title()).isEqualTo("Basics");
            assertThat(sv.lessons()).hasSize(2);
            LearnViewDto.LessonView a = sv.lessons().get(0);
            LearnViewDto.LessonView b = sv.lessons().get(1);
            assertThat(a.id()).isEqualTo(doneContentId.toString());
            assertThat(a.completed()).isTrue();
            assertThat(b.id()).isEqualTo(todoContentId.toString());
            assertThat(b.completed()).isFalse();
        });
    }

    @Test
    void learnView_notEnrolled_throwsNotEnrolled() {
        Course course = course(courseId, SLUG, "Java 101");
        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(enrollments.findByUserIdAndCourseId(currentUserId, courseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.learnView(SLUG))
                .isInstanceOf(NotEnrolledException.class);

        verify(progresses, never()).findByEnrollmentId(any());
    }

    @Test
    void learnView_unknownSlug_throwsCourseNotFound() {
        when(courses.findBySlug(SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.learnView(SLUG))
                .isInstanceOf(CourseNotFoundException.class);
    }

    // =========================================================================
    // markComplete — progress / completion transitions
    // =========================================================================

    @Test
    void markComplete_partialProgress_persistsProgressAndRecomputesPctStaysActive() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId))
                .thenReturn(Optional.of(contentUnderCourse(contentId, courseId)));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        // Course has 4 lessons; 1 now done => 25%.
        when(sections.findByCourseIdWithContents(courseId)).thenReturn(List.of(sectionWith(4)));
        when(progresses.countByEnrollmentIdAndCompletedTrue(enrollmentId)).thenReturn(1L);
        when(enrollments.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));

        ContentProgressDto dto = service.markComplete(enrollmentId, contentId);

        assertThat(dto.contentId()).isEqualTo(contentId.toString());
        assertThat(dto.completed()).isTrue();
        assertThat(dto.completedAt()).isNotNull();

        // Fresh ContentProgress is created bound to this enrollment and content.
        ArgumentCaptor<ContentProgress> cpCaptor = ArgumentCaptor.forClass(ContentProgress.class);
        verify(progresses).save(cpCaptor.capture());
        assertThat(cpCaptor.getValue().getEnrollment()).isSameAs(enr);
        assertThat(cpCaptor.getValue().getContentId()).isEqualTo(contentId);
        assertThat(cpCaptor.getValue().isCompleted()).isTrue();
        assertThat(cpCaptor.getValue().getCompletedAt()).isNotNull();

        ArgumentCaptor<Enrollment> enrCaptor = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollments).save(enrCaptor.capture());
        assertThat(enrCaptor.getValue().getProgressPct()).isEqualTo(25);
        assertThat(enrCaptor.getValue().getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(enrCaptor.getValue().getCompletedAt()).isNull();
        // Below 100% never issues a certificate.
        verifyNoInteractions(certificateService);
    }

    @Test
    void markComplete_lastLesson_completesEnrollmentAndIssuesCertificate() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);

        Course course = course(courseId, SLUG, "Java 101");
        // Passing threshold is set but NOT consulted by markComplete (see concerns):
        // hitting 100% lesson completion issues the certificate regardless.
        course.setCertificationPassingPct(80);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId))
                .thenReturn(Optional.of(contentUnderCourse(contentId, courseId)));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        // 2 lessons total, both now complete => 100%.
        when(sections.findByCourseIdWithContents(courseId)).thenReturn(List.of(sectionWith(2)));
        when(progresses.countByEnrollmentIdAndCompletedTrue(enrollmentId)).thenReturn(2L);
        when(enrollments.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(courses.findById(courseId)).thenReturn(Optional.of(course));
        when(users.findById(currentUserId)).thenReturn(Optional.of(currentUser));

        service.markComplete(enrollmentId, contentId);

        ArgumentCaptor<Enrollment> enrCaptor = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollments).save(enrCaptor.capture());
        assertThat(enrCaptor.getValue().getProgressPct()).isEqualTo(100);
        assertThat(enrCaptor.getValue().getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
        assertThat(enrCaptor.getValue().getCompletedAt()).isNotNull();
        // Certificate issued from the enrollment OWNER, not merely the current principal.
        verify(certificateService).issue(enr, course, currentUser);
    }

    @Test
    void markComplete_certificateFailure_doesNotBlockCompletion() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);
        Course course = course(courseId, SLUG, "Java 101");

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId))
                .thenReturn(Optional.of(contentUnderCourse(contentId, courseId)));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sections.findByCourseIdWithContents(courseId)).thenReturn(List.of(sectionWith(1)));
        when(progresses.countByEnrollmentIdAndCompletedTrue(enrollmentId)).thenReturn(1L);
        when(enrollments.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(courses.findById(courseId)).thenReturn(Optional.of(course));
        when(users.findById(currentUserId)).thenReturn(Optional.of(currentUser));
        // Certificate issuance blows up — completion must still succeed (best-effort).
        when(certificateService.issue(any(), any(), any()))
                .thenThrow(new RuntimeException("pdf render failed"));

        ContentProgressDto dto = service.markComplete(enrollmentId, contentId);

        assertThat(dto.completed()).isTrue();
        assertThat(enr.getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
    }

    @Test
    void markComplete_zeroLessonCourse_skipsRecomputeAndDoesNotSaveEnrollment() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId))
                .thenReturn(Optional.of(contentUnderCourse(contentId, courseId)));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        // Degenerate course with no lessons — recompute divides by total and bails at 0.
        when(sections.findByCourseIdWithContents(courseId)).thenReturn(List.of());

        ContentProgressDto dto = service.markComplete(enrollmentId, contentId);

        assertThat(dto.completed()).isTrue();
        // The lesson progress row is still saved, but the enrollment pct is untouched.
        verify(progresses).save(any());
        verify(enrollments, never()).save(any());
        verify(progresses, never()).countByEnrollmentIdAndCompletedTrue(any());
    }

    @Test
    void markComplete_notOwner_throwsAccessDeniedAndPersistsNothing() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment someoneElses = enrollment(enrollmentId, UUID.randomUUID(), courseId);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.markComplete(enrollmentId, contentId))
                .isInstanceOf(AccessDeniedException.class);

        verify(progresses, never()).save(any());
        verify(enrollments, never()).save(any());
        verifyNoInteractions(contents);
    }

    @Test
    void markComplete_enrollmentNotFound_throwsIllegalArgument() {
        UUID enrollmentId = UUID.randomUUID();
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markComplete(enrollmentId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markComplete_contentNotFound_throwsIllegalArgument() {
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markComplete(enrollmentId, contentId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(progresses, never()).save(any());
    }

    @Test
    void markComplete_contentFromDifferentCourse_throwsBadRequest() {
        // Cross-course guard: completing a foreign course's content could otherwise
        // forge 100% (and a certificate) against THIS enrollment.
        UUID enrollmentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Enrollment enr = enrollment(enrollmentId, currentUserId, courseId);
        UUID otherCourseId = UUID.randomUUID();

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(contents.findByIdWithSectionAndCourse(contentId))
                .thenReturn(Optional.of(contentUnderCourse(contentId, otherCourseId)));

        assertThatThrownBy(() -> service.markComplete(enrollmentId, contentId))
                .isInstanceOf(BadRequestException.class);

        verify(progresses, never()).save(any());
        verify(enrollments, never()).save(any());
    }

    // =========================================================================
    // lessonContent — enrollment / preview access gating
    // =========================================================================

    @Test
    void lessonContent_enrolledLearner_returnsBodyAndResolvedMedia() {
        UUID contentId = UUID.randomUUID();
        Course course = course(courseId, SLUG, "Java 101");
        Content content = contentUnderCourse(contentId, courseId);
        content.setBody("{\"doc\":true}");
        content.setVideoUrl("minio://video");
        content.setAssetUrl("minio://asset");
        content.setDurationMin(12);

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(true);
        when(mediaService.resolveUrl("minio://video")).thenReturn("https://signed/video");
        when(mediaService.resolveUrl("minio://asset")).thenReturn("https://signed/asset");

        LessonContentDto dto = service.lessonContent(SLUG, contentId);

        assertThat(dto.id()).isEqualTo(contentId.toString());
        assertThat(dto.body()).isEqualTo("{\"doc\":true}");
        assertThat(dto.videoUrl()).isEqualTo("https://signed/video");
        assertThat(dto.assetUrl()).isEqualTo("https://signed/asset");
        assertThat(dto.durationMin()).isEqualTo(12);
    }

    @Test
    void lessonContent_notEnrolledNonPreview_throwsNotEnrolled() {
        UUID contentId = UUID.randomUUID();
        Course course = course(courseId, SLUG, "Java 101");
        Content content = contentUnderCourse(contentId, courseId); // allowPreview defaults false

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(false);

        assertThatThrownBy(() -> service.lessonContent(SLUG, contentId))
                .isInstanceOf(NotEnrolledException.class);

        verifyNoInteractions(mediaService);
    }

    @Test
    void lessonContent_previewEnabledPublished_servedToNonEnrolled() {
        // allowPreview honored (gates access), not merely echoed: an unenrolled user
        // may read preview content on a PUBLISHED course.
        UUID contentId = UUID.randomUUID();
        Course course = course(courseId, SLUG, "Java 101");
        course.setState(CourseState.PUBLISHED);
        Content content = contentUnderCourse(contentId, courseId);
        content.setAllowPreview(true);
        content.setBody("preview body");

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(false);

        LessonContentDto dto = service.lessonContent(SLUG, contentId);

        assertThat(dto.preview()).isTrue();
        assertThat(dto.body()).isEqualTo("preview body");
    }

    @Test
    void lessonContent_previewEnabledButDraft_deniedToNonEnrolled() {
        // Preview only unlocks a PUBLISHED course; a DRAFT preview stays locked.
        UUID contentId = UUID.randomUUID();
        Course course = course(courseId, SLUG, "Java 101");
        course.setState(CourseState.DRAFT);
        Content content = contentUnderCourse(contentId, courseId);
        content.setAllowPreview(true);
        // The content's own course chain must also be DRAFT so both course references agree.
        content.getSection().getCourse().setState(CourseState.DRAFT);

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(false);

        assertThatThrownBy(() -> service.lessonContent(SLUG, contentId))
                .isInstanceOf(NotEnrolledException.class);
    }

    @Test
    void lessonContent_contentFromDifferentCourse_throwsIllegalArgument() {
        UUID contentId = UUID.randomUUID();
        Course course = course(courseId, SLUG, "Java 101");
        Content foreign = contentUnderCourse(contentId, UUID.randomUUID());

        when(courses.findBySlug(SLUG)).thenReturn(Optional.of(course));
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.lessonContent(SLUG, contentId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(enrollments, never()).existsByUserIdAndCourseId(any(), any());
    }
}
