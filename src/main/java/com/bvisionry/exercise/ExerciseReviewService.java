package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.CreateExerciseCommentRequest;
import com.bvisionry.exercise.dto.ExerciseCommentResponse;
import com.bvisionry.exercise.dto.ExerciseSubmissionDetailResponse;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseComment;
import com.bvisionry.exercise.entity.ExerciseCommentStatus;
import com.bvisionry.exercise.entity.ExerciseRow;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.notification.push.NotificationType;
import com.bvisionry.notification.push.PushNotificationService;
import com.bvisionry.organization.OrgAuditActions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin review loop over a member's exercise submission: read the sheet, leave
 * comments anchored to a cell / column / row / the whole submission, resolve
 * addressed threads, and drive the status handshake (request changes / mark
 * reviewed). Commenting is allowed in every submission status — the admin can
 * react to saved-but-not-submitted work too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseReviewService {

    private final ExerciseAssignmentService assignmentService;
    private final ExerciseSubmissionService submissionService;
    private final ExerciseSubmissionRepository submissionRepository;
    private final ExerciseRowRepository rowRepository;
    private final ExerciseColumnRepository columnRepository;
    private final ExerciseCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PushNotificationService pushNotificationService;

    @Transactional(readOnly = true)
    public ExerciseSubmissionDetailResponse getSubmission(UUID orgId, UUID assignmentId) {
        return submissionService.buildDetail(requireMemberSubmission(orgId, assignmentId), true);
    }

    @Transactional
    public ExerciseCommentResponse addComment(UUID orgId, UUID assignmentId,
                                              CreateExerciseCommentRequest request) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        User author = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User",
                        String.valueOf(SecurityUtils.getCurrentUserId())));

        ExerciseComment comment = new ExerciseComment();
        comment.setSubmission(submission);
        comment.setAuthor(author);
        comment.setBody(request.body());

        if (request.rowId() != null) {
            ExerciseRow row = rowRepository.findById(request.rowId())
                    .orElseThrow(() -> new ResourceNotFoundException("Row", request.rowId().toString()));
            if (!row.getSubmission().getId().equals(submission.getId())) {
                throw new ResourceNotFoundException("Row", request.rowId().toString());
            }
            comment.setRow(row);
        }
        if (request.columnId() != null) {
            ExerciseColumn column = columnRepository.findById(request.columnId())
                    .orElseThrow(() -> new ResourceNotFoundException("Column",
                            request.columnId().toString()));
            if (!column.getTemplate().getId()
                    .equals(submission.getAssignment().getTemplate().getId())) {
                throw new ResourceNotFoundException("Column", request.columnId().toString());
            }
            comment.setColumn(column);
        }

        // Freeze the commented cell's value so the thread stays readable after
        // the member edits it to address the feedback.
        if (comment.getRow() != null && comment.getColumn() != null
                && comment.getRow().getCells() != null) {
            Object value = comment.getRow().getCells().get(comment.getColumn().getId().toString());
            comment.setCellValueSnapshot(value != null ? String.valueOf(value) : null);
        }

        ExerciseComment saved = commentRepository.save(comment);

        ExerciseAssignment assignment = submission.getAssignment();
        pushNotificationService.notifyUser(submission.getUser().getId(),
                NotificationType.EXERCISE_FEEDBACK,
                "New feedback on your exercise",
                "An admin commented on \"" + assignment.getTemplate().getName() + "\".",
                "/app/exercises/" + submission.getId());

        return ExerciseCommentResponse.from(saved, true);
    }

    @Transactional
    public ExerciseCommentResponse resolveComment(UUID orgId, UUID assignmentId, UUID commentId) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        ExerciseComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId.toString()));
        if (!comment.getSubmission().getId().equals(submission.getId())) {
            throw new ResourceNotFoundException("Comment", commentId.toString());
        }
        if (comment.getParent() != null) {
            throw new BadRequestException("Only root comments can be resolved.");
        }
        comment.setStatus(ExerciseCommentStatus.RESOLVED);
        comment.setResolvedBy(SecurityUtils.getCurrentUserId());
        comment.setResolvedAt(Instant.now());
        return ExerciseCommentResponse.from(comment, true);
    }

    /** SUBMITTED (or already REVIEWED) → CHANGES_REQUESTED: hands the turn back to the member. */
    @Transactional
    public ExerciseSubmissionDetailResponse requestChanges(UUID orgId, UUID assignmentId) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        if (submission.getStatus() != ExerciseSubmissionStatus.SUBMITTED
                && submission.getStatus() != ExerciseSubmissionStatus.REVIEWED) {
            throw new BadRequestException(
                    "Changes can only be requested on a submitted exercise (status was "
                            + submission.getStatus() + ").");
        }
        submission.setStatus(ExerciseSubmissionStatus.CHANGES_REQUESTED);
        submission.setReviewedAt(null);

        notifyStatus(submission, OrgAuditActions.EXERCISE_CHANGES_REQUESTED,
                "Changes requested",
                "An admin requested changes on \"" + templateName(submission) + "\".");
        return submissionService.buildDetail(submission, true);
    }

    /** SUBMITTED → REVIEWED: the loop's terminal state (until the member edits again). */
    @Transactional
    public ExerciseSubmissionDetailResponse markReviewed(UUID orgId, UUID assignmentId) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        if (submission.getStatus() != ExerciseSubmissionStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Only a submitted exercise can be marked reviewed (status was "
                            + submission.getStatus() + ").");
        }
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());

        notifyStatus(submission, OrgAuditActions.EXERCISE_REVIEWED,
                "Exercise reviewed",
                "\"" + templateName(submission) + "\" has been reviewed.");
        return submissionService.buildDetail(submission, true);
    }

    private void notifyStatus(ExerciseSubmission submission, String auditAction,
                              String title, String body) {
        UUID orgId = submission.getAssignment().getOrganization().getId();
        auditService.log(SecurityUtils.getCurrentUserId(), orgId, auditAction,
                OrgAuditActions.ENTITY_EXERCISE_SUBMISSION, submission.getId(),
                Map.of("exerciseName", templateName(submission),
                       "memberName", submission.getUser().getName()));
        pushNotificationService.notifyUser(submission.getUser().getId(),
                NotificationType.EXERCISE_FEEDBACK, title, body,
                "/app/exercises/" + submission.getId());
    }

    private String templateName(ExerciseSubmission submission) {
        return submission.getAssignment().getTemplate().getName();
    }

    private ExerciseSubmission requireMemberSubmission(UUID orgId, UUID assignmentId) {
        ExerciseAssignment assignment = assignmentService.requireAssignmentInOrg(orgId, assignmentId);
        if (assignment.getUser() == null) {
            throw new BadRequestException("This provision has not been assigned to a member yet.");
        }
        return submissionRepository.findByAssignmentId(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", assignmentId.toString()));
    }
}
