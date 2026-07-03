package com.bvisionry.quiz.web;

import static com.bvisionry.quiz.domain.QuestionType.MULTI_CHOICE;
import static com.bvisionry.quiz.domain.QuestionType.SINGLE_CHOICE;
import static com.bvisionry.quiz.domain.QuestionType.TRUE_FALSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.enrollment.web.EnrollmentService;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.quiz.domain.QuestionType;
import com.bvisionry.quiz.domain.Quiz;
import com.bvisionry.quiz.domain.QuizAttempt;
import com.bvisionry.quiz.domain.QuizOption;
import com.bvisionry.quiz.domain.QuizQuestion;
import com.bvisionry.quiz.dto.QuizAttemptResultDto;
import com.bvisionry.quiz.dto.QuizDto;
import com.bvisionry.quiz.dto.QuizTakingDto;
import com.bvisionry.quiz.dto.SubmitQuizAttemptRequest;
import com.bvisionry.quiz.dto.UpsertQuizOptionRequest;
import com.bvisionry.quiz.dto.UpsertQuizQuestionRequest;
import com.bvisionry.quiz.dto.UpsertQuizRequest;
import com.bvisionry.quiz.repository.QuizAttemptRepository;
import com.bvisionry.quiz.repository.QuizRepository;

/**
 * Unit tests for {@link QuizService}. Mirrors the Mockito style of
 * {@code EvaluationServiceTest} (constructor injection via {@link InjectMocks},
 * {@link ArgumentCaptor} on persisted entities, specific state assertions).
 *
 * <p>{@code SecurityUtils} reads the caller from the {@link SecurityContextHolder};
 * rather than static-mocking, each test runs with a real {@link User} principal
 * installed in the context (the same shape {@code JwtAuthenticationFilter} produces),
 * so both {@code getCurrentUserId()} and the imperative org guard resolve naturally.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    @Mock private QuizRepository quizzes;
    @Mock private QuizAttemptRepository attempts;
    @Mock private EnrollmentService enrollmentService;
    @Mock private ContentRepository contents;
    @Mock private EnrollmentRepository enrollments;

    @InjectMocks
    private QuizService service;

    private UUID currentUserId;
    private UUID orgId;
    private Organization org;
    private User currentUser;

    private final UUID contentId = UUID.randomUUID();
    private final UUID enrollmentId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        currentUserId = UUID.randomUUID();

        org = new Organization();
        org.setId(orgId);

        currentUser = new User();
        currentUser.setId(currentUserId);
        currentUser.setEmail("learner@test.com");
        currentUser.setName("Learner");
        // ORG_ADMIN (not SUPER_ADMIN) so the org guard actually compares org ids
        // instead of short-circuiting to allow-all.
        currentUser.setRole(UserRole.ORG_ADMIN);
        currentUser.setStatus(UserStatus.ACTIVE);
        currentUser.setOrganization(org);

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

    // =========================================================================
    // submitAttempt — auto-grading correctness
    // =========================================================================

    @Test
    void submitAttempt_allCorrect_scores100Passes_marksLessonComplete() {
        QuizOption right = option(true);
        QuizOption wrong = option(false);
        QuizQuestion q = question(1, SINGLE_CHOICE, right, wrong);
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), right.getId())));

        assertThat(result.scorePct()).isEqualTo(100);
        assertThat(result.passed()).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.questionResults()).singleElement()
                .satisfies(qr -> {
                    assertThat(qr.questionId()).isEqualTo(q.getId());
                    assertThat(qr.correct()).isTrue();
                });

        QuizAttempt saved = captureSavedAttempt();
        assertThat(saved.getScorePct()).isEqualTo(100);
        assertThat(saved.isPassed()).isTrue();
        assertThat(saved.getUserId()).isEqualTo(currentUserId);
        assertThat(saved.getEnrollmentId()).isEqualTo(enrollmentId);
        assertThat(saved.getContentId()).isEqualTo(contentId);

        // Passing auto-marks the lesson complete via EnrollmentService.
        verify(enrollmentService).markComplete(enrollmentId, contentId);
    }

    @Test
    void submitAttempt_wrongOption_scoresZeroFails_doesNotMarkComplete() {
        QuizOption right = option(true);
        QuizOption wrong = option(false);
        QuizQuestion q = question(1, SINGLE_CHOICE, right, wrong);
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), wrong.getId())));

        assertThat(result.scorePct()).isZero();
        assertThat(result.passed()).isFalse();
        assertThat(result.questionResults().get(0).correct()).isFalse();

        QuizAttempt saved = captureSavedAttempt();
        assertThat(saved.isPassed()).isFalse();
        assertThat(saved.getScorePct()).isZero();
        // A failing attempt must never complete the lesson.
        verify(enrollmentService, never()).markComplete(any(), any());
    }

    @Test
    void submitAttempt_partialWeightedPoints_roundsScoreAndFails() {
        // Question weights differ: a 3-pt question right and a 1-pt question wrong
        // => 3/4 = 75%. Grading is all-or-nothing PER QUESTION (no partial credit
        // within a question), so "partial" means some questions right, some wrong.
        QuizOption bigRight = option(true);
        QuizOption bigWrong = option(false);
        QuizQuestion big = question(3, SINGLE_CHOICE, bigRight, bigWrong);

        QuizOption smallRight = option(true);
        QuizOption smallWrong = option(false);
        QuizQuestion small = question(1, SINGLE_CHOICE, smallRight, smallWrong);

        Quiz quiz = quiz(80, 0, big, small);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request(
                ans(big.getId(), bigRight.getId()),
                ans(small.getId(), smallWrong.getId())));

        assertThat(result.scorePct()).isEqualTo(75); // 3 of 4 points
        assertThat(result.passed()).isFalse();        // 75 < 80
        assertThat(result.questionResults()).hasSize(2);
        assertThat(result.questionResults().get(0).correct()).isTrue();  // big
        assertThat(result.questionResults().get(1).correct()).isFalse(); // small
        verify(enrollmentService, never()).markComplete(any(), any());
    }

    @Test
    void submitAttempt_multiChoiceExactSetMatch_isCorrect() {
        QuizOption c1 = option(true);
        QuizOption c2 = option(true);
        QuizOption w = option(false);
        QuizQuestion q = question(1, MULTI_CHOICE, c1, c2, w);
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request(
                ans(q.getId(), c1.getId()),
                ans(q.getId(), c2.getId())));

        assertThat(result.scorePct()).isEqualTo(100);
        assertThat(result.questionResults().get(0).correct()).isTrue();
    }

    @Test
    void submitAttempt_multiChoiceSubsetSelection_isIncorrect() {
        QuizOption c1 = option(true);
        QuizOption c2 = option(true);
        QuizOption w = option(false);
        QuizQuestion q = question(1, MULTI_CHOICE, c1, c2, w);
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        // Only one of the two required correct options selected => not an exact match.
        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), c1.getId())));

        assertThat(result.scorePct()).isZero();
        assertThat(result.questionResults().get(0).correct()).isFalse();
    }

    @Test
    void submitAttempt_multiChoiceSupersetSelection_isIncorrect() {
        QuizOption c1 = option(true);
        QuizOption c2 = option(true);
        QuizOption w = option(false);
        QuizQuestion q = question(1, MULTI_CHOICE, c1, c2, w);
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        // Both correct options PLUS an extra wrong one => superset, not an exact match.
        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request(
                ans(q.getId(), c1.getId()),
                ans(q.getId(), c2.getId()),
                ans(q.getId(), w.getId())));

        assertThat(result.scorePct()).isZero();
        assertThat(result.questionResults().get(0).correct()).isFalse();
    }

    // ---- passing-score boundary (exactly at / just above / just below) ----

    @Test
    void submitAttempt_scoreExactlyAtPassingThreshold_passes() {
        // 1 of 2 one-point questions correct => 50%. passingScorePct = 50 (boundary):
        // score >= passing must PASS at exact equality.
        assertBoundary(50, 1, 2, /*expectedScore*/ 50, /*expectPass*/ true);
    }

    @Test
    void submitAttempt_scoreJustAbovePassingThreshold_passes() {
        // 2 of 3 correct => round(66.67) = 67. passingScorePct = 66 => passes by 1.
        assertBoundary(66, 2, 3, 67, true);
    }

    @Test
    void submitAttempt_scoreJustBelowPassingThreshold_fails() {
        // 2 of 3 correct => 67. passingScorePct = 68 => fails by 1.
        assertBoundary(68, 2, 3, 67, false);
    }

    // =========================================================================
    // submitAttempt — max-attempts enforcement (0 = unlimited)
    // =========================================================================

    @Test
    void submitAttempt_belowMaxAttempts_allowsResubmissionAndCountsUsed() {
        QuizOption right = option(true);
        QuizQuestion q = question(1, TRUE_FALSE, right, option(false));
        Quiz quiz = quiz(70, 3, q); // limit 3

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        // First call = pre-save limit check (2 used < 3 => allowed).
        // Second call = post-save attemptsUsed (now 3).
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId))
                .thenReturn(2, 3);

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), right.getId())));

        assertThat(result.attemptsUsed()).isEqualTo(3);
        verify(attempts).save(any(QuizAttempt.class));
    }

    @Test
    void submitAttempt_atMaxAttempts_throwsConflict_persistsNothing() {
        Quiz quiz = quiz(70, 3, question(1, SINGLE_CHOICE, option(true), option(false)));

        stubOwnedEnrollment();
        stubQuiz(quiz);
        // Already used exactly the limit => used >= max => reject.
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(3);

        Throwable thrown = catchThrowable(() -> service.submitAttempt(
                enrollmentId, contentId, request()));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(ex.getReason()).contains("Maximum attempts");
        // Nothing graded, nothing saved, lesson never completed.
        verify(attempts, never()).save(any());
        verify(enrollmentService, never()).markComplete(any(), any());
    }

    @Test
    void submitAttempt_overMaxAttempts_throwsConflict() {
        // Defensive: a stored count already past the limit still rejects.
        Quiz quiz = quiz(70, 2, question(1, SINGLE_CHOICE, option(true), option(false)));

        stubOwnedEnrollment();
        stubQuiz(quiz);
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(5);

        Throwable thrown = catchThrowable(() -> service.submitAttempt(
                enrollmentId, contentId, request()));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        verify(attempts, never()).save(any());
    }

    @Test
    void submitAttempt_unlimitedAttempts_neverBlocksRegardlessOfHistory() {
        QuizOption right = option(true);
        QuizQuestion q = question(1, SINGLE_CHOICE, right, option(false));
        Quiz quiz = quiz(70, 0, q); // 0 = unlimited: pre-save count check is skipped

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        // Only the post-save attemptsUsed call happens when maxAttempts == 0.
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(99);

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), right.getId())));

        assertThat(result.attemptsUsed()).isEqualTo(99);
        verify(attempts).save(any(QuizAttempt.class));
    }

    // =========================================================================
    // submitAttempt — ownership / authorization guards
    // =========================================================================

    @Test
    void submitAttempt_enrollmentNotFound_throwsNotFound() {
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.submitAttempt(
                enrollmentId, contentId, request()));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        // Guard fires before any quiz load or grading.
        verifyNoInteractions(quizzes);
        verify(attempts, never()).save(any());
    }

    @Test
    void submitAttempt_notEnrollmentOwner_throwsAccessDenied_beforeLoadingQuiz() {
        Enrollment foreign = new Enrollment();
        foreign.setId(enrollmentId);
        foreign.setUserId(UUID.randomUUID()); // someone else's enrollment
        foreign.setCourseId(courseId);
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.submitAttempt(enrollmentId, contentId, request()))
                .isInstanceOf(AccessDeniedException.class);

        // IDOR guard must reject BEFORE consuming the attempt budget or grading.
        verifyNoInteractions(quizzes);
        verify(attempts, never()).save(any());
        verify(enrollmentService, never()).markComplete(any(), any());
    }

    @Test
    void submitAttempt_quizNotFound_throwsNotFound() {
        stubOwnedEnrollment();
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.submitAttempt(
                enrollmentId, contentId, request()));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(attempts, never()).save(any());
    }

    // =========================================================================
    // submitAttempt — edge cases
    // =========================================================================

    @Test
    void submitAttempt_emptyQuizWithZeroPassingScore_scoresZeroButPasses() {
        // No questions => totalPoints == 0 => scorePct forced to 0, and 0 >= 0 passing
        // => "passed" and lesson auto-completed. Documents CURRENT behavior (see concern).
        Quiz quiz = quiz(0, 0); // passingScorePct = 0, no questions

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request());

        assertThat(result.scorePct()).isZero();
        assertThat(result.passed()).isTrue();
        assertThat(result.questionResults()).isEmpty();
        verify(enrollmentService).markComplete(enrollmentId, contentId);
    }

    @Test
    void submitAttempt_emptyQuizWithNonZeroPassingScore_scoresZeroAndFails() {
        Quiz quiz = quiz(70, 0); // no questions, passing = 70

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request());

        assertThat(result.scorePct()).isZero();
        assertThat(result.passed()).isFalse();
        verify(enrollmentService, never()).markComplete(any(), any());
    }

    @Test
    void submitAttempt_unansweredQuestion_scoredIncorrect() {
        QuizOption a1 = option(true);
        QuizQuestion answered = question(1, SINGLE_CHOICE, a1, option(false));
        QuizQuestion unanswered = question(1, SINGLE_CHOICE, option(true), option(false));
        Quiz quiz = quiz(70, 0, answered, unanswered);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        // Only the first question is answered; the second gets no selection.
        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(answered.getId(), a1.getId())));

        assertThat(result.scorePct()).isEqualTo(50); // 1 of 2 questions
        assertThat(result.passed()).isFalse();
        assertThat(result.questionResults().get(0).correct()).isTrue();
        assertThat(result.questionResults().get(1).correct()).isFalse(); // unanswered => wrong
    }

    @Test
    void submitAttempt_questionWithNoCorrectOption_isAlwaysIncorrect() {
        // Grading-time defense: even if authoring somehow let a question through with
        // no correct option, an empty correct-set can never be matched (correct=false),
        // regardless of what the learner selects.
        QuizOption o1 = option(false);
        QuizOption o2 = option(false);
        QuizQuestion q = question(1, SINGLE_CHOICE, o1, o2);
        Quiz quiz = quiz(1, 0, q); // passing = 1% so only a real point could pass

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, request(ans(q.getId(), o1.getId())));

        assertThat(result.scorePct()).isZero();
        assertThat(result.passed()).isFalse();
        assertThat(result.questionResults().get(0).correct()).isFalse();
    }

    @Test
    void submitAttempt_persistsSubmittedAnswers() {
        QuizOption r1 = option(true);
        QuizOption r2 = option(true);
        QuizQuestion q1 = question(1, SINGLE_CHOICE, r1, option(false));
        QuizQuestion q2 = question(1, SINGLE_CHOICE, r2, option(false));
        Quiz quiz = quiz(70, 0, q1, q2);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        service.submitAttempt(enrollmentId, contentId, request(
                ans(q1.getId(), r1.getId()),
                ans(q2.getId(), r2.getId())));

        QuizAttempt saved = captureSavedAttempt();
        assertThat(saved.getAnswers()).hasSize(2);
        assertThat(saved.getAnswers()).allSatisfy(a -> assertThat(a.getAttempt()).isSameAs(saved));
        assertThat(saved.getAnswers())
                .extracting(a -> a.getQuestionId())
                .containsExactlyInAnyOrder(q1.getId(), q2.getId());
    }

    @Test
    void submitAttempt_emptyAnswers_scoresZeroAndPersistsNoAnswerRows() {
        QuizQuestion q = question(1, SINGLE_CHOICE, option(true), option(false));
        Quiz quiz = quiz(70, 0, q);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        QuizAttemptResultDto result = service.submitAttempt(enrollmentId, contentId, request());

        assertThat(result.scorePct()).isZero();
        assertThat(result.passed()).isFalse();
        QuizAttempt saved = captureSavedAttempt();
        assertThat(saved.getAnswers()).isEmpty();
    }

    // =========================================================================
    // getForTaking — learner-facing read (no correct-answer flags)
    // =========================================================================

    @Test
    void getForTaking_enrolledLearner_returnsQuizWithoutCorrectFlags() {
        Content content = contentInCourse("java-101", courseId);
        QuizOption right = option(true);
        QuizOption wrong = option(false);
        QuizQuestion q = question(2, SINGLE_CHOICE, right, wrong);
        Quiz quiz = quiz(70, 3, q);

        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(true);
        stubQuiz(quiz);

        QuizTakingDto dto = service.getForTaking("java-101", contentId);

        assertThat(dto.contentId()).isEqualTo(quiz.getContentId());
        assertThat(dto.passingScorePct()).isEqualTo(70);
        assertThat(dto.maxAttempts()).isEqualTo(3);
        assertThat(dto.questions()).singleElement().satisfies(qd -> {
            assertThat(qd.type()).isEqualTo("SINGLE_CHOICE");
            assertThat(qd.points()).isEqualTo(2);
            // Taking DTO exposes two options but the type carries no isCorrect field.
            assertThat(qd.options()).hasSize(2);
        });
    }

    @Test
    void getForTaking_notEnrolled_throwsAccessDenied() {
        Content content = contentInCourse("java-101", courseId);
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(false);

        assertThatThrownBy(() -> service.getForTaking("java-101", contentId))
                .isInstanceOf(AccessDeniedException.class);
        // Never reaches the quiz load.
        verifyNoInteractions(quizzes);
    }

    @Test
    void getForTaking_contentBelongsToDifferentCourseThanSlug_throwsNotFound() {
        Content content = contentInCourse("real-course", courseId);
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));

        Throwable thrown = catchThrowable(() -> service.getForTaking("wrong-slug", contentId));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        // Enrollment must not even be consulted once the slug/content mismatch is found.
        verify(enrollments, never()).existsByUserIdAndCourseId(any(), any());
    }

    @Test
    void getForTaking_contentNotFound_throwsNotFound() {
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.getForTaking("java-101", contentId));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void getForTaking_enrolledButNoQuizConfigured_throwsNotFound() {
        Content content = contentInCourse("java-101", courseId);
        when(contents.findByIdWithSectionAndCourse(contentId)).thenReturn(Optional.of(content));
        when(enrollments.existsByUserIdAndCourseId(currentUserId, courseId)).thenReturn(true);
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.getForTaking("java-101", contentId));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // =========================================================================
    // getForAuthoring — org-bound read that DOES expose correct flags
    // =========================================================================

    @Test
    void getForAuthoring_sameOrg_returnsQuizWithCorrectFlags() {
        Content content = contentWithOrg(orgId);
        QuizOption right = option(true);
        QuizOption wrong = option(false);
        QuizQuestion q = question(1, SINGLE_CHOICE, right, wrong);
        Quiz quiz = quiz(70, 0, q);

        when(contents.findById(contentId)).thenReturn(Optional.of(content));
        stubQuiz(quiz);

        QuizDto dto = service.getForAuthoring(contentId);

        assertThat(dto.questions()).singleElement().satisfies(qd -> {
            assertThat(qd.options()).extracting(o -> o.isCorrect())
                    .containsExactlyInAnyOrder(true, false);
        });
    }

    @Test
    void getForAuthoring_foreignOrg_throwsAccessDenied() {
        Content content = contentWithOrg(UUID.randomUUID()); // different org
        when(contents.findById(contentId)).thenReturn(Optional.of(content));

        assertThatThrownBy(() -> service.getForAuthoring(contentId))
                .isInstanceOf(AccessDeniedException.class);
        // Cross-org check gates BEFORE the answer key is loaded.
        verifyNoInteractions(quizzes);
    }

    @Test
    void getForAuthoring_quizNotFound_throwsNotFound() {
        Content content = contentWithOrg(orgId);
        when(contents.findById(contentId)).thenReturn(Optional.of(content));
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.getForAuthoring(contentId));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // =========================================================================
    // upsert — full-replace authoring write
    // =========================================================================

    @Test
    void upsert_newQuiz_persistsConfigAndRebuiltQuestions() {
        Content content = contentWithOrg(orgId);
        when(contents.findById(contentId)).thenReturn(Optional.of(content));
        when(quizzes.findByContentId(contentId)).thenReturn(Optional.empty());
        when(quizzes.save(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));
        // Re-fetch after save falls back to the saved entity when empty.
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.empty());

        UpsertQuizRequest req = new UpsertQuizRequest(80, 2, true, List.of(
                new UpsertQuizQuestionRequest(SINGLE_CHOICE, "2 + 2 = ?", 5, 0, List.of(
                        new UpsertQuizOptionRequest("4", true, 0),
                        new UpsertQuizOptionRequest("5", false, 1)))));

        QuizDto dto = service.upsert(contentId, req);

        // Returned DTO reflects the requested config.
        assertThat(dto.passingScorePct()).isEqualTo(80);
        assertThat(dto.maxAttempts()).isEqualTo(2);
        assertThat(dto.shuffle()).isTrue();
        assertThat(dto.questions()).singleElement().satisfies(qd -> {
            assertThat(qd.prompt()).isEqualTo("2 + 2 = ?");
            assertThat(qd.points()).isEqualTo(5);
            assertThat(qd.options()).hasSize(2);
        });

        // Persisted entity: config applied and options wired to their parent question,
        // which is wired to the quiz.
        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizzes).save(captor.capture());
        Quiz saved = captor.getValue();
        assertThat(saved.getContentId()).isEqualTo(contentId);
        assertThat(saved.getPassingScorePct()).isEqualTo(80);
        assertThat(saved.getMaxAttempts()).isEqualTo(2);
        assertThat(saved.isShuffle()).isTrue();
        assertThat(saved.getQuestions()).singleElement().satisfies(sq -> {
            assertThat(sq.getQuiz()).isSameAs(saved);
            assertThat(sq.getPrompt()).isEqualTo("2 + 2 = ?");
            assertThat(sq.getOptions()).hasSize(2);
            assertThat(sq.getOptions()).allSatisfy(o -> assertThat(o.getQuestion()).isSameAs(sq));
        });
    }

    @Test
    void upsert_existingQuiz_replacesPreviousQuestions() {
        Content content = contentWithOrg(orgId);
        Quiz existing = quiz(50, 1, question(1, SINGLE_CHOICE, option(true), option(false)));
        assertThat(existing.getQuestions()).hasSize(1); // sanity: it starts with an old question

        when(contents.findById(contentId)).thenReturn(Optional.of(content));
        when(quizzes.findByContentId(contentId)).thenReturn(Optional.of(existing));
        when(quizzes.save(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.empty());

        UpsertQuizRequest req = new UpsertQuizRequest(90, 5, false, List.of(
                new UpsertQuizQuestionRequest(TRUE_FALSE, "The sky is blue", 1, 0, List.of(
                        new UpsertQuizOptionRequest("True", true, 0),
                        new UpsertQuizOptionRequest("False", false, 1)))));

        service.upsert(contentId, req);

        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizzes).save(captor.capture());
        Quiz saved = captor.getValue();
        // Full-replace semantics: the old question set is gone, only the new one remains.
        assertThat(saved.getQuestions()).singleElement()
                .satisfies(q -> assertThat(q.getPrompt()).isEqualTo("The sky is blue"));
        assertThat(saved.getPassingScorePct()).isEqualTo(90);
        assertThat(saved.getMaxAttempts()).isEqualTo(5);
    }

    @Test
    void upsert_questionWithoutCorrectOption_rejectedAndNotPersisted() {
        Content content = contentWithOrg(orgId);
        when(contents.findById(contentId)).thenReturn(Optional.of(content));

        UpsertQuizRequest req = new UpsertQuizRequest(70, 0, false, List.of(
                new UpsertQuizQuestionRequest(SINGLE_CHOICE, "Ungradable", 1, 0, List.of(
                        new UpsertQuizOptionRequest("a", false, 0),
                        new UpsertQuizOptionRequest("b", false, 1)))));

        assertThatThrownBy(() -> service.upsert(contentId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at least one correct option");

        verify(quizzes, never()).save(any());
    }

    @Test
    void upsert_foreignOrg_throwsAccessDenied_andPersistsNothing() {
        Content content = contentWithOrg(UUID.randomUUID()); // different org
        when(contents.findById(contentId)).thenReturn(Optional.of(content));

        UpsertQuizRequest req = new UpsertQuizRequest(70, 0, false, List.of(
                new UpsertQuizQuestionRequest(SINGLE_CHOICE, "q", 1, 0, List.of(
                        new UpsertQuizOptionRequest("a", true, 0)))));

        assertThatThrownBy(() -> service.upsert(contentId, req))
                .isInstanceOf(AccessDeniedException.class);

        // Destructive full-replace never runs for a foreign org.
        verify(quizzes, never()).save(any());
        verify(quizzes, never()).findByContentId(any());
    }

    @Test
    void upsert_contentNotFound_throwsNotFound() {
        when(contents.findById(contentId)).thenReturn(Optional.empty());

        UpsertQuizRequest req = new UpsertQuizRequest(70, 0, false, List.of());

        Throwable thrown = catchThrowable(() -> service.upsert(contentId, req));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(quizzes, never()).save(any());
    }

    // =========================================================================
    // Boundary helper
    // =========================================================================

    /**
     * Builds a quiz of {@code total} one-point single-choice questions, answers
     * {@code correctCount} of them correctly, and asserts the resulting score and
     * pass/fail against {@code passingScorePct}.
     */
    private void assertBoundary(int passingScorePct, int correctCount, int total,
                                int expectedScore, boolean expectPass) {
        QuizQuestion[] questions = new QuizQuestion[total];
        UUID[] rightIds = new UUID[total];
        UUID[] wrongIds = new UUID[total];
        for (int i = 0; i < total; i++) {
            QuizOption right = option(true);
            QuizOption wrong = option(false);
            questions[i] = question(1, SINGLE_CHOICE, right, wrong);
            rightIds[i] = right.getId();
            wrongIds[i] = wrong.getId();
        }
        Quiz quiz = quiz(passingScorePct, 0, questions);

        stubOwnedEnrollment();
        stubQuiz(quiz);
        stubAttemptSaveAssignsId();
        when(attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(1);

        SubmitQuizAttemptRequest.QuestionAnswer[] answers =
                new SubmitQuizAttemptRequest.QuestionAnswer[total];
        for (int i = 0; i < total; i++) {
            UUID chosen = i < correctCount ? rightIds[i] : wrongIds[i];
            answers[i] = ans(questions[i].getId(), chosen);
        }

        QuizAttemptResultDto result = service.submitAttempt(
                enrollmentId, contentId, new SubmitQuizAttemptRequest(List.of(answers)));

        assertThat(result.scorePct()).isEqualTo(expectedScore);
        assertThat(result.passed()).isEqualTo(expectPass);
    }

    // =========================================================================
    // Fixture / stub helpers
    // =========================================================================

    private Enrollment ownedEnrollment() {
        Enrollment e = new Enrollment();
        e.setId(enrollmentId);
        e.setUserId(currentUserId);
        e.setCourseId(courseId);
        return e;
    }

    private void stubOwnedEnrollment() {
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(ownedEnrollment()));
    }

    private void stubQuiz(Quiz quiz) {
        when(quizzes.findByContentIdWithQuestionsAndOptions(contentId)).thenReturn(Optional.of(quiz));
    }

    private void stubAttemptSaveAssignsId() {
        when(attempts.save(any(QuizAttempt.class))).thenAnswer(inv -> {
            QuizAttempt a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
    }

    private QuizAttempt captureSavedAttempt() {
        ArgumentCaptor<QuizAttempt> captor = ArgumentCaptor.forClass(QuizAttempt.class);
        verify(attempts).save(captor.capture());
        return captor.getValue();
    }

    private Quiz quiz(int passingScorePct, int maxAttempts, QuizQuestion... questions) {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setContentId(contentId);
        quiz.setPassingScorePct(passingScorePct);
        quiz.setMaxAttempts(maxAttempts);
        quiz.setShuffle(false);
        for (QuizQuestion q : questions) {
            q.setQuiz(quiz);
            quiz.getQuestions().add(q);
        }
        return quiz;
    }

    private QuizQuestion question(int points, QuestionType type, QuizOption... options) {
        QuizQuestion q = new QuizQuestion();
        q.setId(UUID.randomUUID());
        q.setType(type);
        q.setPrompt("prompt");
        q.setPoints(points);
        for (QuizOption o : options) {
            o.setQuestion(q);
            q.getOptions().add(o);
        }
        return q;
    }

    private QuizOption option(boolean correct) {
        QuizOption o = new QuizOption();
        o.setId(UUID.randomUUID());
        o.setText("option");
        o.setCorrect(correct);
        return o;
    }

    private SubmitQuizAttemptRequest request(SubmitQuizAttemptRequest.QuestionAnswer... answers) {
        return new SubmitQuizAttemptRequest(List.of(answers));
    }

    private SubmitQuizAttemptRequest.QuestionAnswer ans(UUID questionId, UUID optionId) {
        return new SubmitQuizAttemptRequest.QuestionAnswer(questionId, optionId);
    }

    private Content contentWithOrg(UUID contentOrgId) {
        Content content = new Content();
        content.setId(contentId);
        content.setOrgId(contentOrgId);
        return content;
    }

    private Content contentInCourse(String slug, UUID cId) {
        Course course = new Course();
        course.setId(cId);
        course.setSlug(slug);
        Section section = new Section();
        section.setCourse(course);
        Content content = new Content();
        content.setId(contentId);
        content.setOrgId(orgId);
        content.setSection(section);
        return content;
    }
}
