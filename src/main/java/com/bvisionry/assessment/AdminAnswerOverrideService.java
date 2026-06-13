package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.OverrideAnswersRequest;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.evaluation.SubmissionPillarUnlockRepository;
import com.bvisionry.evaluation.entity.SubmissionPillarUnlock;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Super-admin-only path for overriding a member's answers on an evaluated
 * submission and re-running the AI evaluation.
 *
 * <p>Two operations:
 * <ol>
 *   <li>{@link #overrideAnswers} — write new values for one pillar's answers.
 *       Auto-unlocks the pillar if it isn't already unlocked, transitioning
 *       the submission to {@code PENDING_REEDIT}. Saving does <strong>not</strong>
 *       trigger AI re-evaluation; the admin batches edits across pillars and
 *       triggers re-eval explicitly.</li>
 *   <li>{@link #triggerReevaluation} — flips {@code PENDING_REEDIT → SUBMITTED}
 *       and dispatches the async evaluation. The existing partial-re-eval path
 *       in {@link EvaluationService} sees the unlock rows, snapshots the prior
 *       evaluations to history, and re-scores only the unlocked pillars.</li>
 * </ol>
 *
 * <p>Tenant scoping is enforced on every call by matching the assignment's
 * organization against the {@code orgId} path variable. The controller layer
 * additionally gates with {@code @PreAuthorize("hasAuthority('SUPER_ADMIN')")}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAnswerOverrideService {

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;
    private final SubmissionPillarUnlockRepository unlockRepository;
    private final EvaluationService evaluationService;

    /**
     * Override the member's answers for a single pillar. Auto-unlocks the
     * pillar if it isn't already in the unlock set so the partial re-eval
     * path picks it up when the admin later triggers re-evaluation.
     */
    @Transactional
    public void overrideAnswers(UUID orgId, UUID assignmentId, UUID pillarId,
                                OverrideAnswersRequest request) {
        Submission submission = loadSubmissionForOrg(orgId, assignmentId);

        SubmissionStatus status = submission.getStatus();
        if (status != SubmissionStatus.EVALUATED && status != SubmissionStatus.PENDING_REEDIT) {
            throw new BadRequestException(
                    "Answers can only be overridden on an evaluated submission (was "
                            + status + ").");
        }

        Pipeline pipeline = submission.getAssignment().getPipeline();
        Pillar pillar = pipeline.getPillars().stream()
                .filter(p -> p.getId().equals(pillarId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Pillar " + pillarId + " does not belong to this pipeline."));
        if (pillar.getType() == PillarType.PERSONAL) {
            throw new BadRequestException(
                    "Personal pillars can't be overridden — they carry profile data, not an evaluation.");
        }

        Map<UUID, Question> questionsById = pillar.getQuestions().stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        // Validate the whole batch first so a half-applied save can't leave the
        // submission in a mixed state.
        for (OverrideAnswersRequest.AnswerEntry entry : request.answers()) {
            if (!questionsById.containsKey(entry.questionId())) {
                throw new BadRequestException(
                        "Question " + entry.questionId() + " does not belong to pillar " + pillarId + ".");
            }
        }

        ensurePillarUnlocked(submission, pillar);

        Map<UUID, Answer> existingByQuestion = answerRepository
                .findBySubmissionId(submission.getId()).stream()
                .filter(a -> questionsById.containsKey(a.getQuestion().getId()))
                .collect(Collectors.toMap(
                        a -> a.getQuestion().getId(),
                        a -> a,
                        (existing, replacement) ->
                                replacement.getUpdatedAt().isAfter(existing.getUpdatedAt())
                                        ? replacement
                                        : existing));

        for (OverrideAnswersRequest.AnswerEntry entry : request.answers()) {
            Answer answer = existingByQuestion.computeIfAbsent(entry.questionId(), qid -> {
                Answer a = new Answer();
                a.setSubmission(submission);
                Question ref = new Question();
                ref.setId(qid);
                a.setQuestion(ref);
                return a;
            });
            answer.setResponseText(entry.responseText());
            answer.setSelectedValue(entry.selectedValue());
            answerRepository.save(answer);
        }

        log.info("Super-admin overrode {} answer(s) on submission {} pillar {}",
                request.answers().size(), submission.getId(), pillarId);
    }

    /**
     * Manually trigger re-evaluation after one or more answer overrides. Flips
     * status from {@code PENDING_REEDIT} to {@code SUBMITTED} so
     * {@link EvaluationService#evaluateSubmissionAsync} runs the partial-re-eval
     * path. The async dispatch is deferred until after this transaction commits
     * so the worker observes the status flip.
     */
    @Transactional
    public void triggerReevaluation(UUID orgId, UUID assignmentId) {
        Submission submission = loadSubmissionForOrg(orgId, assignmentId);
        if (submission.getStatus() != SubmissionStatus.PENDING_REEDIT) {
            throw new BadRequestException(
                    "Re-evaluation can only be triggered on a submission in PENDING_REEDIT (was "
                            + submission.getStatus() + ").");
        }
        if (unlockRepository.findUnlockedPillarIds(submission.getId()).isEmpty()) {
            throw new BadRequestException(
                    "No pillars are currently unlocked — nothing to re-evaluate.");
        }

        UUID submissionId = submission.getId();
        submission.setStatus(SubmissionStatus.SUBMITTED);
        // Clear stale evaluatedAt so the timeline doesn't show a completion
        // timestamp on a step that's running again. Intentionally NOT touching
        // submittedAt — it pins the original submission moment, matching the
        // member-driven re-edit path.
        submission.setEvaluatedAt(null);
        submissionRepository.save(submission);

        AfterCommit.run(() -> evaluationService.evaluateSubmissionAsync(submissionId));

        log.info("Super-admin triggered re-evaluation for submission {}", submissionId);
    }

    private void ensurePillarUnlocked(Submission submission, Pillar pillar) {
        Set<UUID> alreadyUnlocked = Set.copyOf(unlockRepository.findUnlockedPillarIds(submission.getId()));
        if (alreadyUnlocked.contains(pillar.getId())) {
            return;
        }
        SubmissionPillarUnlock row = new SubmissionPillarUnlock();
        row.setSubmission(submission);
        row.setPillar(pillar);
        row.setUnlockedByAdminId(currentAdminIdOrNull());
        row.setReason("Super-admin override");
        row.setUnlockedAt(Instant.now());
        unlockRepository.save(row);

        if (submission.getStatus() == SubmissionStatus.EVALUATED) {
            submission.setStatus(SubmissionStatus.PENDING_REEDIT);
            submissionRepository.save(submission);
        }
    }

    private Submission loadSubmissionForOrg(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        // Admin overrides always target the latest attempt — older attempts are
        // historical and overwriting their answers would be invisible to the
        // member.
        return submissionRepository.requireLatestForAssignment(
                assignment, "No submission exists for this assignment.");
    }

    private static UUID currentAdminIdOrNull() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
