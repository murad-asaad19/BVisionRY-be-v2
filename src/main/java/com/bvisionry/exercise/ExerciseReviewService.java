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
 * Review loop over a member's exercise submission: read the sheet, leave
 * comments anchored to a cell / column / row / the whole submission, resolve
 * addressed threads, and drive the status handshake (request changes / mark
 * reviewed). Commenting is allowed in every submission status — the reviewer
 * can react to saved-but-not-submitted work too.
 *
 * <p>Reviewers are org admins and, since the coach console, COACHes — a coach
 * only reaches submissions of founders inside their assignment union
 * ({@link CoachAccess}); anything else is a 404 at the data layer.
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

    /**
     * Setter-injected on purpose: this class's constructor signature is pinned
     * verbatim in the frozen ArchUnit store (its cross-feature parameters are
     * recorded violations, and the store is append-never), so it cannot grow a
     * constructor parameter without minting "new" violations. The gate is a
     * same-feature bean, so the edge itself is legal.
     */
    private ExerciseCoachGate coachGate;

    @org.springframework.beans.factory.annotation.Autowired
    void setCoachGate(ExerciseCoachGate coachGate) {
        this.coachGate = coachGate;
    }

    /** Setter-injected for the same frozen-signature reason as {@link #coachGate}. */
    private QualityTagCatalog qualityTagCatalog;

    @org.springframework.beans.factory.annotation.Autowired
    void setQualityTagCatalog(QualityTagCatalog qualityTagCatalog) {
        this.qualityTagCatalog = qualityTagCatalog;
    }

    /**
     * The §7b actor for the quality tag. Deliberately the shared-kernel port and
     * not {@code SecurityUtils}: a new exercise→auth call site would mint a new
     * frozen ArchUnit violation for an edge that has a legal alternative sitting
     * right here (the sibling submission service already uses it).
     */
    private com.bvisionry.common.security.CurrentUserAccessor currentUserAccessor;

    @org.springframework.beans.factory.annotation.Autowired
    void setCurrentUserAccessor(com.bvisionry.common.security.CurrentUserAccessor accessor) {
        this.currentUserAccessor = accessor;
    }

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

        // A reply inherits its root's anchor and thread — explicit anchors
        // would let a reply drift to a different cell than its thread.
        if (request.parentId() != null) {
            if (request.rowId() != null || request.columnId() != null) {
                throw new BadRequestException("A reply cannot set its own anchor.");
            }
            ExerciseComment root = commentRepository.findById(request.parentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Comment",
                            request.parentId().toString()));
            if (!root.getSubmission().getId().equals(submission.getId())) {
                throw new ResourceNotFoundException("Comment", request.parentId().toString());
            }
            if (root.getParent() != null) {
                throw new BadRequestException("Reply to the thread's root comment.");
            }
            comment.setParent(root);
            comment.setRow(root.getRow());
            comment.setColumn(root.getColumn());
        }

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
        // the member edits it to address the feedback (roots only — replies
        // inherit their thread's context).
        if (request.parentId() == null
                && comment.getRow() != null && comment.getColumn() != null
                && comment.getRow().getCells() != null) {
            Object value = comment.getRow().getCells().get(comment.getColumn().getId().toString());
            comment.setCellValueSnapshot(value != null ? String.valueOf(value) : null);
        }

        ExerciseComment saved = commentRepository.save(comment);

        ExerciseAssignment assignment = submission.getAssignment();
        pushNotificationService.notifyUser(submission.getUser().getId(),
                NotificationType.EXERCISE_FEEDBACK,
                "New feedback on your exercise",
                "Your reviewer commented on \"" + assignment.getTemplate().getName() + "\".",
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
        // Durable, never cleared: the queue's "resubmitted after changes"
        // marker reads this — reviewedAt can't carry it, both the resubmit
        // and this method null that.
        submission.setChangesRequestedAt(Instant.now());

        notifyStatus(submission, OrgAuditActions.EXERCISE_CHANGES_REQUESTED,
                "Changes requested",
                "Your reviewer requested changes on \"" + templateName(submission) + "\".");
        return submissionService.buildDetail(submission, true);
    }

    /**
     * SUBMITTED → REVIEWED: the loop's terminal state (until the member edits
     * again). {@code qualityTagKey} is optional (spec §4): the tag rides the
     * review because that is when the reviewer has an opinion; a null leaves
     * whatever tag is already there alone — clearing is an explicit
     * {@link #setQualityTag} call, not a side effect of re-reviewing.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse markReviewed(UUID orgId, UUID assignmentId,
                                                         String qualityTagKey) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        if (submission.getStatus() != ExerciseSubmissionStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Only a submitted exercise can be marked reviewed (status was "
                            + submission.getStatus() + ").");
        }
        if (qualityTagKey != null) {
            stampQualityTag(submission, qualityTagKey);
        }
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());

        notifyStatus(submission, OrgAuditActions.EXERCISE_REVIEWED,
                "Exercise reviewed",
                "\"" + templateName(submission) + "\" has been reviewed.");
        return submissionService.buildDetail(submission, true);
    }

    /**
     * Set, change or clear ({@code null} key) the quality tag on an already
     * reviewed submission — re-tagging is allowed and re-stamps §7b. Metadata
     * only: nothing here touches status, the member, or the participation
     * score, so it sends no notification and the member is never told.
     *
     * <p>REVIEWED is required because the tag is a statement about work the
     * reviewer has finished reading. A copy the member has since edited is back
     * in SUBMITTED and gets its tag through {@link #markReviewed} again.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse setQualityTag(UUID orgId, UUID assignmentId,
                                                          String qualityTagKey) {
        ExerciseSubmission submission = requireMemberSubmission(orgId, assignmentId);
        if (submission.getStatus() != ExerciseSubmissionStatus.REVIEWED) {
            throw new BadRequestException(
                    "Only a reviewed exercise can be tagged (status was "
                            + submission.getStatus() + "). Mark it reviewed to tag it.");
        }
        if (qualityTagKey == null) {
            submission.setQualityTagKey(null);
            submission.setQualityTagLabel(null);
            submission.setQualityTaggedAt(null);
            submission.setQualityTaggedBy(null);
        } else {
            stampQualityTag(submission, qualityTagKey);
        }
        return submissionService.buildDetail(submission, true);
    }

    /**
     * Validates against the CURRENT §7 tag set and snapshots its label, so a
     * later rename or deletion never rewrites what the reviewer said.
     */
    private void stampQualityTag(ExerciseSubmission submission, String qualityTagKey) {
        submission.setQualityTagLabel(qualityTagCatalog.requireLabel(qualityTagKey));
        submission.setQualityTagKey(qualityTagKey);
        submission.setQualityTaggedAt(Instant.now());
        submission.setQualityTaggedBy(currentUserAccessor.require().userId());
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
        // Data layer of the coach's three-layer defense: a COACH caller only
        // reaches submissions of founders inside their assignment union — a
        // uniform 404 outside it, so foreign work is absent, not forbidden.
        coachGate.requireCoachMaySeeSubmission(orgId, assignmentId);
        return submissionRepository.findByAssignmentId(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", assignmentId.toString()));
    }
}
