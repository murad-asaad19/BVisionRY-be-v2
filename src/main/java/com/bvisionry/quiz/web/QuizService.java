package com.bvisionry.quiz.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.enrollment.web.EnrollmentService;
import com.bvisionry.quiz.domain.Quiz;
import com.bvisionry.quiz.domain.QuizAttempt;
import com.bvisionry.quiz.domain.QuizAttemptAnswer;
import com.bvisionry.quiz.domain.QuizOption;
import com.bvisionry.quiz.domain.QuizQuestion;
import com.bvisionry.quiz.dto.QuizAttemptResultDto;
import com.bvisionry.quiz.dto.QuizDto;
import com.bvisionry.quiz.dto.QuizOptionDto;
import com.bvisionry.quiz.dto.QuizOptionTakingDto;
import com.bvisionry.quiz.dto.QuizQuestionDto;
import com.bvisionry.quiz.dto.QuizQuestionTakingDto;
import com.bvisionry.quiz.dto.QuizTakingDto;
import com.bvisionry.quiz.dto.SubmitQuizAttemptRequest;
import com.bvisionry.quiz.dto.UpsertQuizOptionRequest;
import com.bvisionry.quiz.dto.UpsertQuizQuestionRequest;
import com.bvisionry.quiz.dto.UpsertQuizRequest;
import com.bvisionry.quiz.repository.QuizAttemptRepository;
import com.bvisionry.quiz.repository.QuizRepository;

/**
 * Application service covering quiz authoring, taking, and auto-grading.
 */
@Service
public class QuizService {

    private final QuizRepository quizzes;
    private final QuizAttemptRepository attempts;
    private final EnrollmentService enrollmentService;
    private final ContentRepository contents;
    private final EnrollmentRepository enrollments;

    public QuizService(QuizRepository quizzes,
                       QuizAttemptRepository attempts,
                       EnrollmentService enrollmentService,
                       ContentRepository contents,
                       EnrollmentRepository enrollments) {
        this.quizzes = quizzes;
        this.attempts = attempts;
        this.enrollmentService = enrollmentService;
        this.contents = contents;
        this.enrollments = enrollments;
    }

    // -------------------------------------------------------------------------
    // Authoring
    // -------------------------------------------------------------------------

    /**
     * Returns the full quiz definition including correct-answer flags.
     * For SUPER_ADMIN / INSTRUCTOR use only.
     */
    @Transactional(readOnly = true)
    public QuizDto getForAuthoring(UUID contentId) {
        // SECURITY: the @PreAuthorize role check is org-agnostic, so an INSTRUCTOR
        // from one org could otherwise read another org's answer keys. Bind the
        // request to the content's org before exposing correct-answer flags.
        Content content = requireContent(contentId);
        SecurityUtils.requireOrgAccess(content.getOrgId());

        Quiz quiz = quizzes.findByContentIdWithQuestionsAndOptions(contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No quiz found for content: " + contentId));
        return toAuthoringDto(quiz);
    }

    /**
     * Create-or-replace the quiz configuration, questions, and options for the
     * given content item. Full replace semantics: existing questions/options are
     * dropped and rebuilt from the request.
     */
    @Transactional
    public QuizDto upsert(UUID contentId, UpsertQuizRequest request) {
        // SECURITY: role-only authorization would let an INSTRUCTOR overwrite a
        // foreign org's quiz. Bind the destructive full-replace write to the
        // content's org before mutating anything.
        Content content = requireContent(contentId);
        SecurityUtils.requireOrgAccess(content.getOrgId());

        // CORRECTNESS: a question with zero correct options can never be answered
        // correctly (grading requires the chosen set to equal a non-empty correct
        // set), silently capping the achievable score. Reject at authoring time.
        validateEachQuestionHasCorrectOption(request);

        Quiz quiz = quizzes.findByContentId(contentId)
                .orElseGet(() -> {
                    Quiz fresh = new Quiz();
                    fresh.setContentId(contentId);
                    return fresh;
                });

        quiz.setPassingScorePct(request.passingScorePct());
        quiz.setMaxAttempts(request.maxAttempts());
        quiz.setShuffle(request.shuffle());

        // Full replace: clear existing questions (cascade deletes options)
        quiz.getQuestions().clear();

        for (UpsertQuizQuestionRequest qReq : request.questions()) {
            QuizQuestion question = new QuizQuestion();
            question.setQuiz(quiz);
            question.setType(qReq.type());
            question.setPrompt(qReq.prompt());
            question.setPoints(qReq.points());
            question.setSequence(qReq.sequence());

            for (var oReq : qReq.options()) {
                QuizOption option = new QuizOption();
                option.setQuestion(question);
                option.setText(oReq.text());
                option.setCorrect(oReq.isCorrect());
                option.setSequence(oReq.sequence());
                question.getOptions().add(option);
            }

            quiz.getQuestions().add(question);
        }

        Quiz saved = quizzes.save(quiz);
        // Re-fetch with eager collections for accurate DTO mapping
        Quiz fetched = quizzes.findByContentIdWithQuestionsAndOptions(saved.getContentId())
                .orElse(saved);
        return toAuthoringDto(fetched);
    }

    // -------------------------------------------------------------------------
    // Taking
    // -------------------------------------------------------------------------

    /**
     * Returns the quiz definition WITHOUT correct-answer flags. Safe to expose to
     * learners who are enrolled in the {@code slug} course that owns the content.
     */
    @Transactional(readOnly = true)
    public QuizTakingDto getForTaking(String slug, UUID contentId) {
        // SECURITY: previously any authenticated user could fetch any quiz by
        // contentId. Bind the request to the {slug} course that owns the content and
        // require the caller to be enrolled in that course. Courses are a PUBLIC,
        // cross-org catalog, so this learner-facing read must NOT be gated by org
        // membership: an enrolled member of any org may take the quiz. Enrollment is
        // the correct gate (an unenrolled caller of any org is still rejected below).
        Content content = contents.findByIdWithSectionAndCourse(contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Content not found: " + contentId));

        var course = content.getSection().getCourse();
        if (!course.getSlug().equals(slug)) {
            // The content does not belong to the course in the URL path.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Quiz not found for course: " + slug);
        }

        UUID userId = SecurityUtils.getCurrentUserId();
        if (!enrollments.existsByUserIdAndCourseId(userId, course.getId())) {
            throw new AccessDeniedException("Not enrolled in this course");
        }

        Quiz quiz = quizzes.findByContentIdWithQuestionsAndOptions(contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No quiz found for content: " + contentId));
        return toTakingDto(quiz);
    }

    // -------------------------------------------------------------------------
    // Submit attempt + auto-grade
    // -------------------------------------------------------------------------

    /**
     * Grades the submitted answers, persists the attempt, enforces max-attempts,
     * and marks the lesson complete when the learner passes.
     */
    @Transactional
    public QuizAttemptResultDto submitAttempt(UUID enrollmentId,
                                              UUID contentId,
                                              SubmitQuizAttemptRequest request) {

        // SECURITY: the path enrollmentId was previously trusted, allowing an IDOR
        // where a caller could submit attempts against (and consume the attempt
        // budget of) someone else's enrollment. Verify ownership BEFORE counting
        // attempts or persisting anything. Mirrors PlaybackService.requireOwnership.
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Enrollment not found: " + enrollmentId));

        UUID userId = SecurityUtils.getCurrentUserId();
        if (!enrollment.getUserId().equals(userId)) {
            throw new AccessDeniedException("Not your enrollment");
        }

        Quiz quiz = quizzes.findByContentIdWithQuestionsAndOptions(contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No quiz found for content: " + contentId));

        // Enforce max_attempts (0 = unlimited)
        if (quiz.getMaxAttempts() > 0) {
            int used = attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId);
            if (used >= quiz.getMaxAttempts()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Maximum attempts (" + quiz.getMaxAttempts() + ") reached for this quiz.");
            }
        }

        // Build lookup: questionId → selected optionIds
        Map<UUID, Set<UUID>> selectedByQuestion = request.answers().stream()
                .collect(Collectors.groupingBy(
                        SubmitQuizAttemptRequest.QuestionAnswer::questionId,
                        Collectors.mapping(
                                SubmitQuizAttemptRequest.QuestionAnswer::optionId,
                                Collectors.toSet())));

        // Grade: for each question, a MULTI_CHOICE question is correct only when
        // the selected set exactly equals the correct set; same rule applies for
        // SINGLE_CHOICE and TRUE_FALSE (they just have one correct option).
        int totalPoints = 0;
        int earnedPoints = 0;
        List<QuizAttemptResultDto.QuestionResult> questionResults = new ArrayList<>();

        for (QuizQuestion question : quiz.getQuestions()) {
            totalPoints += question.getPoints();

            Set<UUID> correctOptionIds = question.getOptions().stream()
                    .filter(QuizOption::isCorrect)
                    .map(QuizOption::getId)
                    .collect(Collectors.toSet());

            Set<UUID> chosen = selectedByQuestion.getOrDefault(question.getId(), Set.of());
            boolean correct = !correctOptionIds.isEmpty() && chosen.equals(correctOptionIds);

            if (correct) {
                earnedPoints += question.getPoints();
            }
            questionResults.add(new QuizAttemptResultDto.QuestionResult(question.getId(), correct));
        }

        int scorePct = totalPoints == 0 ? 0 : (int) Math.round((earnedPoints * 100.0) / totalPoints);
        boolean passed = scorePct >= quiz.getPassingScorePct();

        // Persist attempt
        QuizAttempt attempt = new QuizAttempt();
        attempt.setContentId(contentId);
        attempt.setUserId(userId);
        attempt.setEnrollmentId(enrollmentId);
        attempt.setScorePct(scorePct);
        attempt.setPassed(passed);

        // Persist attempt answers
        for (SubmitQuizAttemptRequest.QuestionAnswer ans : request.answers()) {
            QuizAttemptAnswer answer = new QuizAttemptAnswer();
            answer.setAttempt(attempt);
            answer.setQuestionId(ans.questionId());
            answer.setOptionId(ans.optionId());
            attempt.getAnswers().add(answer);
        }

        QuizAttempt saved = attempts.save(attempt);

        // Count total attempts used (after save)
        int attemptsUsed = attempts.countByEnrollmentIdAndContentId(enrollmentId, contentId);

        // If passed → mark lesson complete via EnrollmentService
        if (passed) {
            enrollmentService.markComplete(enrollmentId, contentId);
        }

        return new QuizAttemptResultDto(
                saved.getId(),
                scorePct,
                passed,
                attemptsUsed,
                questionResults);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Loads the parent content or 404s. Used to resolve the org for access checks. */
    private Content requireContent(UUID contentId) {
        return contents.findById(contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Content not found: " + contentId));
    }

    /**
     * Rejects any question that has no option flagged correct: such a question is
     * ungradable (it can never be answered correctly) and would silently cap the
     * quiz's maximum achievable score.
     */
    private void validateEachQuestionHasCorrectOption(UpsertQuizRequest request) {
        for (int i = 0; i < request.questions().size(); i++) {
            UpsertQuizQuestionRequest qReq = request.questions().get(i);
            boolean hasCorrect = qReq.options().stream()
                    .anyMatch(UpsertQuizOptionRequest::isCorrect);
            if (!hasCorrect) {
                throw new IllegalArgumentException(
                        "Question " + (i + 1) + " must have at least one correct option.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    private QuizDto toAuthoringDto(Quiz quiz) {
        List<QuizQuestionDto> questionDtos = quiz.getQuestions().stream()
                .map(q -> new QuizQuestionDto(
                        q.getId(),
                        q.getType().name(),
                        q.getPrompt(),
                        q.getPoints(),
                        q.getSequence(),
                        q.getOptions().stream()
                                .map(o -> new QuizOptionDto(
                                        o.getId(), o.getText(), o.isCorrect(), o.getSequence()))
                                .toList()))
                .toList();

        return new QuizDto(
                quiz.getId(),
                quiz.getContentId(),
                quiz.getPassingScorePct(),
                quiz.getMaxAttempts(),
                quiz.isShuffle(),
                questionDtos);
    }

    private QuizTakingDto toTakingDto(Quiz quiz) {
        List<QuizQuestionTakingDto> questionDtos = quiz.getQuestions().stream()
                .map(q -> new QuizQuestionTakingDto(
                        q.getId(),
                        q.getType().name(),
                        q.getPrompt(),
                        q.getPoints(),
                        q.getSequence(),
                        q.getOptions().stream()
                                .map(o -> new QuizOptionTakingDto(
                                        o.getId(), o.getText(), o.getSequence()))
                                .toList()))
                .toList();

        return new QuizTakingDto(
                quiz.getId(),
                quiz.getContentId(),
                quiz.getPassingScorePct(),
                quiz.getMaxAttempts(),
                quiz.isShuffle(),
                questionDtos);
    }
}
