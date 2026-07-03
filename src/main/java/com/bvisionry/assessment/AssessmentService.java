package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SaveAnswerRequest;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.evaluation.SubmissionPillarUnlockRepository;
import com.bvisionry.notification.push.NotificationType;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.dto.PostCompletionLinkDto;
import com.bvisionry.pipeline.repository.QuestionRepository;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentService {

    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final SubmissionPillarUnlockRepository unlockRepository;
    private final EvaluationService evaluationService;
    private final PostCompletionLinkResolver postCompletionLinkResolver;
    private final AuditService auditService;
    private final PushNotificationService pushNotificationService;

    @Transactional(readOnly = true)
    public List<AssessmentSummaryResponse> listAssessments(UUID userId) {
        List<Submission> submissions = submissionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (submissions.isEmpty()) {
            return List.of();
        }

        // Batched answered-count lookup — single GROUP BY query replaces the
        // previous per-submission countBySubmissionId loop.
        List<UUID> submissionIds = submissions.stream().map(Submission::getId).toList();
        Map<UUID, Long> answeredCounts = answerRepository.findAnsweredCountsBySubmissionIds(submissionIds)
                .stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        // Per-assignment check-in number (1-indexed, oldest = 1) and "is latest"
        // flag so the member UI can render "Check-in N of M" and gate the
        // "Start New Check-In" button on the most recent row only. The parent
        // list is already DESC by createdAt, so the first row encountered per
        // assignmentId is the latest; remaining rows count down.
        Map<UUID, Integer> remainingByAssignment = new java.util.HashMap<>();
        for (Submission s : submissions) {
            remainingByAssignment.merge(s.getAssignment().getId(), 1, Integer::sum);
        }
        Map<UUID, Integer> checkInNumberBySubmission = new java.util.HashMap<>();
        Set<UUID> latestSubmissionIds = new java.util.HashSet<>();
        Map<UUID, Integer> seenPerAssignment = new java.util.HashMap<>();
        for (Submission s : submissions) {
            UUID assignmentId = s.getAssignment().getId();
            int total = remainingByAssignment.get(assignmentId);
            int seen = seenPerAssignment.merge(assignmentId, 1, Integer::sum);
            checkInNumberBySubmission.put(s.getId(), total - seen + 1);
            if (seen == 1) {
                latestSubmissionIds.add(s.getId());
            }
        }

        return submissions.stream()
                .map(sub -> toSummary(sub,
                        answeredCounts.getOrDefault(sub.getId(), 0L),
                        checkInNumberBySubmission.getOrDefault(sub.getId(), 1),
                        latestSubmissionIds.contains(sub.getId())))
                .toList();
    }

    /**
     * Builds the member-facing summary row for a single submission. Extracted
     * so {@link AssignmentService#startNewCheckIn} can return the same shape
     * the assessment list uses without re-running the batched count query.
     */
    AssessmentSummaryResponse toSummary(Submission sub, long answeredQuestions, int checkInNumber, boolean isLatest) {
        Pipeline pipeline = sub.getAssignment().getPipeline();
        int totalQuestions = pipeline.getPillars().stream()
                .mapToInt(p -> p.getQuestions().size())
                .sum();
        return new AssessmentSummaryResponse(
                sub.getId(),
                sub.getAssignment().getId(),
                pipeline.getId(),
                pipeline.getName(),
                pipeline.getDescription(),
                sub.getStatus(),
                sub.getAssignment().getDeadline(),
                totalQuestions,
                (int) answeredQuestions,
                sub.getStartedAt(),
                sub.getSubmittedAt(),
                sub.getEvaluatedAt(),
                checkInNumber,
                sub.getAssignment().getMaxCheckIns(),
                isLatest
        );
    }

    @Transactional(readOnly = true)
    public AssessmentDetailResponse getAssessment(UUID submissionId, UUID userId) {
        Submission submission = getSubmissionForUser(submissionId, userId);
        return buildAssessmentDetail(submission);
    }

    /**
     * Admin read path — skips the user-ownership check. Callers are expected
     * to have already enforced org-scoping (see
     * {@link com.bvisionry.assessment.AssignmentController}).
     */
    @Transactional(readOnly = true)
    public AssessmentDetailResponse getAssessmentForAdmin(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        return buildAssessmentDetail(submission);
    }

    private AssessmentDetailResponse buildAssessmentDetail(Submission submission) {
        Assignment assignment = submission.getAssignment();
        Pipeline pipeline = assignment.getPipeline();

        List<Answer> answers = answerRepository.findBySubmissionId(submission.getId());
        Map<UUID, Answer> answersByQuestion = answers.stream()
                .collect(Collectors.toMap(
                        a -> a.getQuestion().getId(),
                        a -> a,
                        AssessmentService::mostRecentAnswer));

        List<AssessmentDetailResponse.PillarSection> pillarSections = pipeline.getPillars().stream()
                .sorted(Comparator.comparingInt(Pillar::getDisplayOrder))
                .map(pillar -> {
                    List<AssessmentDetailResponse.QuestionWithAnswer> questionsWithAnswers =
                            pillar.getQuestions().stream()
                                    .sorted(Comparator.comparingInt(Question::getDisplayOrder))
                                    .map(q -> {
                                        Answer answer = answersByQuestion.get(q.getId());
                                        AssessmentDetailResponse.AnswerData answerData = answer != null
                                                ? new AssessmentDetailResponse.AnswerData(
                                                        answer.getId(),
                                                        answer.getResponseText(),
                                                        answer.getSelectedValue())
                                                : null;

                                        return new AssessmentDetailResponse.QuestionWithAnswer(
                                                q.getId(),
                                                q.getType(),
                                                q.getPromptText(),
                                                q.getDisplayOrder(),
                                                q.isRequired(),
                                                q.getConfigJson(),
                                                answerData
                                        );
                                    })
                                    .toList();

                    return new AssessmentDetailResponse.PillarSection(
                            pillar.getId(),
                            pillar.getName(),
                            pillar.getDescription(),
                            pillar.getIconKey(),
                            pillar.getDisplayOrder(),
                            pillar.getType().name(),
                            questionsWithAnswers
                    );
                })
                .toList();

        // Only fetch unlock rows when the submission is actually in re-edit
        // mode — for every other state the list is always empty and skipping
        // the query keeps the GET-assessment hot path lean.
        List<UUID> unlockedPillarIds = submission.getStatus() == SubmissionStatus.PENDING_REEDIT
                ? unlockRepository.findUnlockedPillarIds(submission.getId())
                : List.of();

        return new AssessmentDetailResponse(
                submission.getId(),
                assignment.getId(),
                submission.getStatus(),
                assignment.getDeadline(),
                new AssessmentDetailResponse.PipelineInfo(
                        pipeline.getId(), pipeline.getName(), pipeline.getDescription()),
                pillarSections,
                unlockedPillarIds
        );
    }

    @Transactional
    public AnswerResponse saveAnswer(UUID submissionId, UUID questionId, UUID userId,
                                     SaveAnswerRequest request) {
        Submission submission = getSubmissionForUser(submissionId, userId);
        EditableScope scope = validateEditable(submission);
        requireQuestionEditable(scope, questionId);

        return saveAnswerInternal(submission, questionId, request);
    }

    @Transactional
    public List<AnswerResponse> batchSaveAnswers(UUID submissionId, UUID userId,
                                                  BatchSaveAnswersRequest request) {
        Submission submission = getSubmissionForUser(submissionId, userId);
        EditableScope scope = validateEditable(submission);
        // Validate the whole batch up-front so a half-applied save can't leave
        // the submission in a mixed state.
        if (!scope.allowsAll()) {
            List<UUID> questionIds = request.answers().stream()
                    .map(BatchSaveAnswersRequest.AnswerEntry::questionId)
                    .toList();
            Map<UUID, Question> byId = questionRepository.findAllById(questionIds).stream()
                    .collect(Collectors.toMap(Question::getId, q -> q));
            for (UUID qid : questionIds) {
                Question q = byId.get(qid);
                if (q == null) {
                    throw new ResourceNotFoundException("Question", qid.toString());
                }
                if (!scope.unlockedPillarIds().contains(q.getPillar().getId())) {
                    throw new BadRequestException(
                            "This pillar is locked. Only pillars unlocked by an admin can be edited.");
                }
            }
        }

        return request.answers().stream()
                .map(entry -> {
                    SaveAnswerRequest singleRequest = new SaveAnswerRequest(
                            entry.responseText(), entry.selectedValue());
                    return saveAnswerInternal(submission, entry.questionId(), singleRequest);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewResponse getReview(UUID submissionId, UUID userId) {
        Submission submission = getSubmissionForUser(submissionId, userId);
        Pipeline pipeline = submission.getAssignment().getPipeline();
        List<Answer> answers = answerRepository.findBySubmissionId(submissionId);

        Set<UUID> answeredQuestionIds = answers.stream()
                .filter(this::hasContent)
                .map(a -> a.getQuestion().getId())
                .collect(Collectors.toSet());

        List<Question> requiredQuestions = pipeline.getPillars().stream()
                .flatMap(p -> p.getQuestions().stream())
                .filter(Question::isRequired)
                .toList();

        List<ReviewResponse.UnansweredQuestion> unanswered = requiredQuestions.stream()
                .filter(q -> !answeredQuestionIds.contains(q.getId()))
                .map(q -> new ReviewResponse.UnansweredQuestion(
                        q.getId(),
                        q.getPromptText(),
                        q.getPillar().getId(),
                        q.getPillar().getName()))
                .toList();

        int totalRequired = requiredQuestions.size();
        int answeredRequired = totalRequired - unanswered.size();

        return new ReviewResponse(
                submissionId,
                unanswered.isEmpty(),
                totalRequired,
                answeredRequired,
                unanswered
        );
    }

    @Transactional
    public SubmitAssessmentResponse submitAssessment(UUID submissionId, UUID userId) {
        Submission submission = getSubmissionForUser(submissionId, userId);

        if (submission.getStatus() == SubmissionStatus.SUBMITTED) {
            throw new BadRequestException("Assessment is currently being evaluated. Please wait.");
        }
        if (submission.getStatus() == SubmissionStatus.EVALUATED) {
            throw new BadRequestException("Assessment has already been evaluated.");
        }
        // Allow re-submit from IN_PROGRESS, FAILED, and PENDING_REEDIT.
        // PENDING_REEDIT routes through the same async pipeline, but the
        // evaluation engine in EvaluationService scopes the work to the
        // unlocked pillars only and regenerates the OverallSummary.

        // Verify all required questions are answered
        ReviewResponse review = getReview(submissionId, userId);
        if (!review.complete()) {
            throw new BadRequestException(
                    "Cannot submit: " + review.unansweredQuestions().size() + " unanswered required questions");
        }

        // Lock + enqueue: flip to SUBMITTED and clear any stale failure/claim state
        // from a prior attempt so this submit is immediately claimable (see
        // Submission#queueForEvaluation). submittedAt pins this submission moment.
        submission.queueForEvaluation();
        submission.setSubmittedAt(Instant.now());
        submissionRepository.save(submission);

        auditService.log(userId, submission.getAssignment().getOrganization().getId(),
                OrgAuditActions.ASSESSMENT_SUBMITTED,
                OrgAuditActions.ENTITY_SUBMISSION, submissionId,
                Map.of("pipelineName", submission.getAssignment().getPipeline().getName()));

        // Capture plain values for the post-commit lambda — the entities are
        // detached once the transaction closes.
        UUID orgId = submission.getAssignment().getOrganization().getId();
        String memberName = submission.getUser().getName();
        String pipelineName = submission.getAssignment().getPipeline().getName();

        AfterCommit.dispatch(() -> {
            log.info("Transaction committed for submission {}, dispatching async evaluation", submissionId);
            evaluationService.evaluateSubmissionAsync(submissionId);
            pushNotificationService.notifyOrgAdmins(orgId, NotificationType.MEMBER_SUBMITTED,
                    "Assessment completed",
                    memberName + " completed \"" + pipelineName + "\".",
                    "/app/admin/assignments",
                    "/app/admin/organizations/" + orgId + "/assignments");
        });

        log.info("Submission {} submitted and enqueued for evaluation", submissionId);

        // Only surface EXTERNAL post-completion redirects on submit. SURVEY
        // pairings are delivered by a dedicated email after evaluation
        // finishes, so we don't show an in-app CTA here.
        PostCompletionLinkDto postCompletion = postCompletionLinkResolver
                .resolveExternal(submission.getAssignment().getPipeline())
                .orElse(null);

        return new SubmitAssessmentResponse(
                submissionId,
                SubmissionStatus.SUBMITTED,
                submission.getSubmittedAt(),
                "Assessment submitted successfully. Evaluation in progress.",
                postCompletion
        );
    }

    @Transactional(readOnly = true)
    public SubmissionStatusResponse getStatus(UUID submissionId, UUID userId) {
        Submission submission = getSubmissionForUser(submissionId, userId);

        return new SubmissionStatusResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getSubmittedAt(),
                submission.getEvaluatedAt()
        );
    }

    // --- Private helpers ---

    private Submission getSubmissionForUser(UUID submissionId, UUID userId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));

        // Public (anonymous) submissions have no owning user — treat them like
        // any other foreign submission (404, never reveal existence).
        if (submission.getUser() == null || !submission.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Submission", submissionId.toString());
        }

        return submission;
    }

    /**
     * Decide whether the member is allowed to edit this submission's answers
     * right now, and if so, which questions are in scope. Returns either
     * {@code EditableScope.all()} (full edit, IN_PROGRESS) or a scoped value
     * containing the set of pillar IDs an admin has unlocked (PENDING_REEDIT).
     * Throws {@link BadRequestException} for any other state.
     */
    private EditableScope validateEditable(Submission submission) {
        SubmissionStatus status = submission.getStatus();
        if (status == SubmissionStatus.IN_PROGRESS) {
            requireDeadlineNotPassed(submission);
            return EditableScope.all();
        }
        if (status == SubmissionStatus.PENDING_REEDIT) {
            // Re-edit windows ignore the original deadline — admin re-opening
            // the assessment is the explicit authorization to edit again.
            List<UUID> unlocked = unlockRepository.findUnlockedPillarIds(submission.getId());
            if (unlocked.isEmpty()) {
                // Defensive: should be impossible (relock clears + flips status),
                // but if it ever happens we'd rather refuse than silently allow.
                throw new BadRequestException(
                        "Re-edit was opened but no pillars are currently unlocked.");
            }
            return EditableScope.scoped(Set.copyOf(unlocked));
        }
        throw new BadRequestException("Assessment has already submitted and cannot be modified");
    }

    private void requireDeadlineNotPassed(Submission submission) {
        Instant effectiveDeadline = submission.getEffectiveDeadline();
        if (effectiveDeadline != null && java.time.Instant.now().isAfter(effectiveDeadline)) {
            throw new BadRequestException("The assessment deadline has passed.");
        }
    }

    private void requireQuestionEditable(EditableScope scope, UUID questionId) {
        if (scope.allowsAll()) return;
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId.toString()));
        UUID pillarId = question.getPillar().getId();
        if (!scope.unlockedPillarIds().contains(pillarId)) {
            throw new BadRequestException(
                    "This pillar is locked. Only pillars unlocked by an admin can be edited.");
        }
    }

    /**
     * The set of questions the caller is currently allowed to edit. Either
     * "everything" (when {@link #allowsAll()} is true) or restricted to the
     * questions whose pillar is in {@link #unlockedPillarIds()}.
     */
    private record EditableScope(boolean allowsAll, Set<UUID> unlockedPillarIds) {
        static EditableScope all() {
            return new EditableScope(true, Set.of());
        }
        static EditableScope scoped(Set<UUID> ids) {
            return new EditableScope(false, ids);
        }
    }

    /**
     * Reject answers for questions that don't belong to this submission's
     * pipeline. Without this, the Answer FK only checks that the questionId
     * exists somewhere — a client could persist an Answer against a foreign
     * pipeline's question. requireQuestionEditable is a no-op for IN_PROGRESS
     * (full edit) submissions, so this is the only membership gate on that path.
     */
    private void requireQuestionInPipeline(Submission submission, UUID questionId) {
        boolean belongs = submission.getAssignment().getPipeline().getPillars().stream()
                .flatMap(pillar -> pillar.getQuestions().stream())
                .anyMatch(q -> q.getId().equals(questionId));
        if (!belongs) {
            throw new ResourceNotFoundException("Question", questionId.toString());
        }
    }

    private AnswerResponse saveAnswerInternal(Submission submission, UUID questionId,
                                               SaveAnswerRequest request) {
        requireQuestionInPipeline(submission, questionId);
        Answer answer = answerRepository.findBySubmissionIdAndQuestionId(submission.getId(), questionId)
                .orElseGet(() -> {
                    Answer newAnswer = new Answer();
                    newAnswer.setSubmission(submission);
                    Question questionRef = new Question();
                    questionRef.setId(questionId);
                    newAnswer.setQuestion(questionRef);
                    return newAnswer;
                });

        answer.setResponseText(request.responseText());
        answer.setSelectedValue(request.selectedValue());
        answer = answerRepository.save(answer);

        return new AnswerResponse(
                answer.getId(),
                questionId,
                answer.getResponseText(),
                answer.getSelectedValue(),
                answer.getUpdatedAt()
        );
    }

    private boolean hasContent(Answer answer) {
        return (answer.getResponseText() != null && !answer.getResponseText().isBlank())
                || (answer.getSelectedValue() != null && !answer.getSelectedValue().isBlank());
    }

    /**
     * Merge function for de-duplicating answers keyed by question id. A unique
     * constraint (see V86) now prevents duplicate rows, but legacy data and the
     * rare upsert race can still surface two answers for one question — keep the
     * most recently updated one rather than letting the stream collector throw.
     */
    private static Answer mostRecentAnswer(Answer existing, Answer replacement) {
        return replacement.getUpdatedAt().isAfter(existing.getUpdatedAt())
                ? replacement
                : existing;
    }
}
