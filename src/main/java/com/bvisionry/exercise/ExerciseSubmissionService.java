package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.ExerciseColumnResponse;
import com.bvisionry.exercise.dto.ExerciseCommentResponse;
import com.bvisionry.exercise.dto.ExerciseRowPayload;
import com.bvisionry.exercise.dto.ExerciseRowResponse;
import com.bvisionry.exercise.dto.ExerciseSubmissionDetailResponse;
import com.bvisionry.exercise.dto.MyExerciseSummaryResponse;
import com.bvisionry.exercise.dto.SaveExerciseRowsRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseComment;
import com.bvisionry.exercise.entity.ExerciseCommentStatus;
import com.bvisionry.exercise.entity.ExerciseRow;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Member-facing exercise flow: list my exercises, load the sheet, autosave
 * rows, submit for review, and reply to admin feedback. Also owns the shared
 * detail builder the admin review side reuses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseSubmissionService {

    private final ExerciseSubmissionRepository submissionRepository;
    private final ExerciseRowRepository rowRepository;
    private final ExerciseColumnRepository columnRepository;
    private final ExerciseCommentRepository commentRepository;
    private final AuditService auditService;
    private final PushNotificationService pushNotificationService;

    @Transactional(readOnly = true)
    public List<MyExerciseSummaryResponse> listMine(UUID userId) {
        List<ExerciseSubmission> submissions = submissionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, Long> openCounts = openCommentCounts(
                submissions.stream().map(ExerciseSubmission::getId).toList());
        return submissions.stream()
                .map(s -> {
                    ExerciseTemplate template = s.getAssignment().getTemplate();
                    return new MyExerciseSummaryResponse(
                            s.getId(),
                            template.getId(),
                            template.getName(),
                            template.getDescription(),
                            s.getStatus(),
                            s.getAssignment().getDeadline(),
                            openCounts.getOrDefault(s.getId(), 0L),
                            s.getLastSavedAt(),
                            s.getSubmittedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ExerciseSubmissionDetailResponse getForMember(UUID submissionId, UUID userId) {
        return buildDetail(requireOwned(submissionId, userId), false);
    }

    /**
     * Replace-all save of the sheet, in display order. Existing row ids are
     * updated in place (keeping their comment anchors); omitted rows are
     * hard-deleted when uncommented and soft-deleted otherwise, so no thread
     * ever loses its target. Allowed in every status — members can edit at
     * any time, including after review.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse saveRows(UUID submissionId, UUID userId,
                                                     SaveExerciseRowsRequest request) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        Set<String> columnIds = new HashSet<>();
        for (ExerciseColumn column : templateColumns(submission)) {
            columnIds.add(column.getId().toString());
        }

        Map<UUID, ExerciseRow> existingById = new HashMap<>();
        List<ExerciseRow> existing = rowRepository.findBySubmissionId(submissionId);
        existing.forEach(r -> existingById.put(r.getId(), r));
        Set<UUID> commentedRowIds = new HashSet<>(commentRepository.findCommentedRowIds(submissionId));

        Set<UUID> kept = new HashSet<>();
        int order = 0;
        for (ExerciseRowPayload payload : request.rows()) {
            ExerciseRow row;
            if (payload.id() != null) {
                row = existingById.get(payload.id());
                if (row == null) {
                    throw new BadRequestException("Unknown row: " + payload.id());
                }
                row.setDeletedAt(null);
                kept.add(payload.id());
            } else {
                row = new ExerciseRow();
                row.setSubmission(submission);
            }
            row.setCells(sanitizeCells(payload.cells(), columnIds));
            row.setDisplayOrder(order++);
            rowRepository.save(row);
        }

        for (ExerciseRow row : existing) {
            if (kept.contains(row.getId()) || row.isDeleted()) {
                continue;
            }
            if (commentedRowIds.contains(row.getId())) {
                row.setDeletedAt(Instant.now());
            } else {
                rowRepository.delete(row);
            }
        }

        submission.setLastSavedAt(Instant.now());
        return buildDetail(submission, false);
    }

    @Transactional
    public ExerciseSubmissionDetailResponse submit(UUID submissionId, UUID userId) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        if (submission.getStatus() != ExerciseSubmissionStatus.IN_PROGRESS
                && submission.getStatus() != ExerciseSubmissionStatus.CHANGES_REQUESTED) {
            throw new BadRequestException(
                    "This exercise is already submitted (status was " + submission.getStatus() + ").");
        }

        List<ExerciseRow> liveRows =
                rowRepository.findBySubmissionIdAndDeletedAtIsNullOrderByDisplayOrder(submissionId);
        if (liveRows.isEmpty()) {
            throw new BadRequestException("Add at least one row before submitting.");
        }
        for (ExerciseColumn column : templateColumns(submission)) {
            if (!column.isRequired()) {
                continue;
            }
            String key = column.getId().toString();
            for (ExerciseRow row : liveRows) {
                Object value = row.getCells() != null ? row.getCells().get(key) : null;
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new BadRequestException(
                            "\"" + column.getName() + "\" is required — fill it in every row before submitting.");
                }
            }
        }

        submission.setStatus(ExerciseSubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(Instant.now());

        ExerciseTemplate template = submission.getAssignment().getTemplate();
        UUID orgId = submission.getAssignment().getOrganization().getId();
        auditService.log(userId, orgId, OrgAuditActions.EXERCISE_SUBMITTED,
                OrgAuditActions.ENTITY_EXERCISE_SUBMISSION, submission.getId(),
                Map.of("exerciseName", template.getName(),
                       "memberName", submission.getUser().getName()));
        pushNotificationService.notifyOrgAdmins(orgId, NotificationType.EXERCISE_ACTIVITY,
                "Exercise submitted",
                submission.getUser().getName() + " submitted \"" + template.getName() + "\".",
                "/app/admin/exercises",
                "/app/admin/exercises");

        return buildDetail(submission, false);
    }

    /** Member reply on an admin's root comment — "addressed, see the updated value". */
    @Transactional
    public ExerciseCommentResponse reply(UUID submissionId, UUID userId, UUID commentId, String body) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        ExerciseComment root = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId.toString()));
        if (!root.getSubmission().getId().equals(submissionId)) {
            throw new ResourceNotFoundException("Comment", commentId.toString());
        }
        if (root.getParent() != null) {
            throw new BadRequestException("Reply to the thread's root comment.");
        }

        ExerciseComment replyComment = new ExerciseComment();
        replyComment.setSubmission(submission);
        replyComment.setAuthor(submission.getUser());
        replyComment.setParent(root);
        replyComment.setRow(root.getRow());
        replyComment.setColumn(root.getColumn());
        replyComment.setBody(body);
        ExerciseComment saved = commentRepository.save(replyComment);

        ExerciseTemplate template = submission.getAssignment().getTemplate();
        pushNotificationService.notifyOrgAdmins(
                submission.getAssignment().getOrganization().getId(),
                NotificationType.EXERCISE_ACTIVITY,
                "Exercise feedback reply",
                submission.getUser().getName() + " replied on \"" + template.getName() + "\".",
                "/app/admin/exercises",
                "/app/admin/exercises");

        return ExerciseCommentResponse.from(saved, false);
    }

    // --- shared with the admin review side ---------------------------------

    /**
     * Everything one screen needs in one payload. {@code forAdmin} additionally
     * exposes the member's name/email (the member already knows their own).
     */
    @Transactional(readOnly = true)
    public ExerciseSubmissionDetailResponse buildDetail(ExerciseSubmission submission, boolean forAdmin) {
        ExerciseTemplate template = submission.getAssignment().getTemplate();

        List<ExerciseColumnResponse> columns = templateColumns(submission).stream()
                .map(ExerciseColumnResponse::from)
                .toList();

        // Deleted rows ride along (flagged) so comment threads anchored to a
        // removed row can still show their context.
        List<ExerciseRowResponse> rows = rowRepository.findBySubmissionId(submission.getId()).stream()
                .sorted(Comparator.comparingInt(ExerciseRow::getDisplayOrder))
                .map(ExerciseRowResponse::from)
                .toList();

        List<ExerciseCommentResponse> comments = commentRepository
                .findBySubmissionIdOrderByCreatedAt(submission.getId()).stream()
                .map(c -> ExerciseCommentResponse.from(c, isAdmin(c.getAuthor())))
                .toList();

        User member = submission.getUser();
        return new ExerciseSubmissionDetailResponse(
                submission.getId(),
                submission.getAssignment().getId(),
                template.getId(),
                template.getName(),
                template.getDescription(),
                submission.getStatus(),
                submission.getAssignment().getDeadline(),
                submission.getLastSavedAt(),
                submission.getSubmittedAt(),
                submission.getReviewedAt(),
                forAdmin ? member.getName() : null,
                forAdmin ? member.getEmail() : null,
                columns,
                rows,
                comments);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> openCommentCounts(List<UUID> submissionIds) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        if (submissionIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : commentRepository.countOpenBySubmissionIdIn(submissionIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private List<ExerciseColumn> templateColumns(ExerciseSubmission submission) {
        return columnRepository.findByTemplateIdOrderByDisplayOrder(
                submission.getAssignment().getTemplate().getId());
    }

    /** Values are kept as sent; keys that don't match a real column are dropped. */
    private Map<String, Object> sanitizeCells(Map<String, Object> cells, Set<String> columnIds) {
        if (cells == null) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        cells.forEach((key, value) -> {
            if (columnIds.contains(key) && value != null) {
                clean.put(key, value);
            }
        });
        return clean;
    }

    private static boolean isAdmin(User user) {
        return user.getRole() == UserRole.ORG_ADMIN || user.getRole() == UserRole.SUPER_ADMIN;
    }

    /**
     * Ownership gate for every member operation: a missing id and someone
     * else's submission are both "not found" so ids can't be probed.
     */
    private ExerciseSubmission requireOwned(UUID submissionId, UUID userId) {
        ExerciseSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", submissionId.toString()));
        if (!submission.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Exercise", submissionId.toString());
        }
        return submission;
    }
}
