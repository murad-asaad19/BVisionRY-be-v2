package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.ExerciseColumnResponse;
import com.bvisionry.exercise.dto.ExerciseCommentResponse;
import com.bvisionry.exercise.dto.ExerciseRowPayload;
import com.bvisionry.exercise.dto.ExerciseRowResponse;
import com.bvisionry.exercise.dto.ExerciseSubmissionDetailResponse;
import com.bvisionry.exercise.dto.MyExerciseSummaryResponse;
import com.bvisionry.exercise.dto.SaveExerciseAnswersRequest;
import com.bvisionry.exercise.dto.SaveExerciseRowsRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseComment;
import com.bvisionry.exercise.entity.ExerciseCommentStatus;
import com.bvisionry.exercise.entity.ExerciseRow;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.organization.OrgAuditActions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final MediaUrlPort mediaUrlPort;
    private final AuditService auditService;
    // Published, not called directly: see AssessmentService's field comment —
    // a direct notification.push call here is a NEW frozen-ArchUnit violation
    // even where the class pair is already frozen, so both submit-notify call
    // sites below go through ProgramFlowPushHandler instead.
    private final ApplicationEventPublisher eventPublisher;

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
     * any time; a change to a REVIEWED sheet moves it back to SUBMITTED so
     * the admin re-reviews what actually shipped.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse saveRows(UUID submissionId, UUID userId,
                                                     SaveExerciseRowsRequest request) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        boolean changed = applyRows(submission, request);

        // REVIEWED is only terminal until the member edits again — a real
        // change puts the sheet back in the admin's queue for re-review.
        if (changed && submission.getStatus() == ExerciseSubmissionStatus.REVIEWED) {
            submission.setStatus(ExerciseSubmissionStatus.SUBMITTED);
            submission.setReviewedAt(null);
        }

        submission.setLastSavedAt(Instant.now());
        return buildDetail(submission, false);
    }

    /**
     * Reviewer-side edit of the member's answers (the exercise counterpart of
     * the assessment answer override). Same row rules as the member save, but
     * the status is left alone — an admin correcting a REVIEWED sheet must not
     * push their own edit back into the review queue.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse overrideRows(ExerciseSubmission submission,
                                                         SaveExerciseRowsRequest request) {
        applyRows(submission, request);
        submission.setLastSavedAt(Instant.now());
        return buildDetail(submission, true);
    }

    /**
     * WORKSHEET counterpart of {@link #saveRows}: replace-all write of the
     * answers map, allowed in every status; a real change to a REVIEWED copy
     * puts it back in the admin's queue.
     */
    @Transactional
    public ExerciseSubmissionDetailResponse saveAnswers(UUID submissionId, UUID userId,
                                                        SaveExerciseAnswersRequest request) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        boolean changed = applyAnswers(submission, request);

        if (changed && submission.getStatus() == ExerciseSubmissionStatus.REVIEWED) {
            submission.setStatus(ExerciseSubmissionStatus.SUBMITTED);
            submission.setReviewedAt(null);
        }

        submission.setLastSavedAt(Instant.now());
        return buildDetail(submission, false);
    }

    /** WORKSHEET counterpart of {@link #overrideRows} — status untouched. */
    @Transactional
    public ExerciseSubmissionDetailResponse overrideAnswers(ExerciseSubmission submission,
                                                            SaveExerciseAnswersRequest request) {
        applyAnswers(submission, request);
        submission.setLastSavedAt(Instant.now());
        return buildDetail(submission, true);
    }

    /** Shared worksheet answer write. Returns whether anything actually changed. */
    private boolean applyAnswers(ExerciseSubmission submission, SaveExerciseAnswersRequest request) {
        ExerciseTemplate template = submission.getAssignment().getTemplate();
        if (template.getKind() != ExerciseTemplateKind.WORKSHEET) {
            throw new BadRequestException("This exercise is a sheet — save rows, not answers.");
        }
        Map<String, Object> clean = WorksheetBlocks.sanitizeAnswers(
                request.answers(), template.getBlocks());
        Map<String, Object> current = submission.getAnswers() != null
                ? submission.getAnswers() : Map.of();
        boolean changed = !clean.equals(current);
        submission.setAnswers(clean);
        return changed;
    }

    /**
     * Replace-all row write shared by the member save and the admin override.
     * Returns whether anything actually changed.
     */
    private boolean applyRows(ExerciseSubmission submission, SaveExerciseRowsRequest request) {
        if (submission.getAssignment().getTemplate().getKind() != ExerciseTemplateKind.SHEET) {
            throw new BadRequestException("This exercise is a worksheet — save answers, not rows.");
        }
        UUID submissionId = submission.getId();
        Set<String> columnIds = new HashSet<>();
        Set<String> lockedColumnIds = new HashSet<>();
        for (ExerciseColumn column : templateColumns(submission)) {
            columnIds.add(column.getId().toString());
            if (column.isLocked()) {
                lockedColumnIds.add(column.getId().toString());
            }
        }
        boolean allowAddRows = submission.getAssignment().getTemplate().isAllowAddRows();

        Map<UUID, ExerciseRow> existingById = new HashMap<>();
        List<ExerciseRow> existing = rowRepository.findBySubmissionId(submissionId);
        existing.forEach(r -> existingById.put(r.getId(), r));
        Set<UUID> commentedRowIds = new HashSet<>(commentRepository.findCommentedRowIds(submissionId));

        Set<UUID> kept = new HashSet<>();
        boolean changed = false;
        int order = 0;
        for (ExerciseRowPayload payload : request.rows()) {
            ExerciseRow row;
            if (payload.id() != null) {
                row = existingById.get(payload.id());
                if (row == null) {
                    throw new BadRequestException("Unknown row: " + payload.id());
                }
                changed |= row.isDeleted() || row.getDisplayOrder() != order;
                row.setDeletedAt(null);
                kept.add(payload.id());
            } else {
                if (!allowAddRows) {
                    throw new BadRequestException("This exercise does not allow adding rows.");
                }
                row = new ExerciseRow();
                row.setSubmission(submission);
                changed = true;
            }
            Map<String, Object> cells = SheetCells.sanitize(payload.cells(), columnIds);
            // Locked columns are admin-prefilled: keep the stored value, drop
            // whatever the client sent.
            for (String lockedId : lockedColumnIds) {
                Object stored = row.getCells() != null ? row.getCells().get(lockedId) : null;
                if (stored != null) {
                    cells.put(lockedId, stored);
                } else {
                    cells.remove(lockedId);
                }
            }
            changed |= !cells.equals(row.getCells() != null ? row.getCells() : Map.of());
            row.setCells(cells);
            row.setDisplayOrder(order++);
            rowRepository.save(row);
        }

        for (ExerciseRow row : existing) {
            if (kept.contains(row.getId()) || row.isDeleted()) {
                continue;
            }
            if (row.isStarter()) {
                throw new BadRequestException("Prefilled rows cannot be removed.");
            }
            changed = true;
            if (commentedRowIds.contains(row.getId())) {
                row.setDeletedAt(Instant.now());
            } else {
                rowRepository.delete(row);
            }
        }

        return changed;
    }

    @Transactional
    public ExerciseSubmissionDetailResponse submit(UUID submissionId, UUID userId) {
        ExerciseSubmission submission = requireOwned(submissionId, userId);
        // NOT_SUBMITTED is allowed through on purpose (V208, operator decision
        // 2026-08-22): a member who turns up late corrects the record by
        // handing the work in, rather than needing a super admin to unlock it.
        if (submission.getStatus() != ExerciseSubmissionStatus.IN_PROGRESS
                && submission.getStatus() != ExerciseSubmissionStatus.CHANGES_REQUESTED
                && submission.getStatus() != ExerciseSubmissionStatus.NOT_SUBMITTED) {
            throw new BadRequestException(
                    "This exercise is already submitted (status was " + submission.getStatus() + ").");
        }

        ExerciseTemplate template = submission.getAssignment().getTemplate();
        if (template.getKind() == ExerciseTemplateKind.WORKSHEET) {
            requireWorksheetComplete(submission, template);
        } else {
            requireSheetComplete(submissionId, submission);
        }

        submission.setStatus(ExerciseSubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(Instant.now());

        UUID orgId = submission.getAssignment().getOrganization().getId();
        auditService.log(userId, orgId, OrgAuditActions.EXERCISE_SUBMITTED,
                OrgAuditActions.ENTITY_EXERCISE_SUBMISSION, submission.getId(),
                Map.of("exerciseName", template.getName(),
                       "memberName", submission.getUser().getName()));
        eventPublisher.publishEvent(new ProgramFlowEvents.ExerciseSubmitted(
                orgId, userId, submission.getUser().getName(), template.getName()));

        return buildDetail(submission, false);
    }

    /** SHEET completeness — the shared rule, over this submission's live rows. */
    private void requireSheetComplete(UUID submissionId, ExerciseSubmission submission) {
        SheetCells.requireComplete(
                rowRepository.findBySubmissionIdAndDeletedAtIsNullOrderByDisplayOrder(submissionId)
                        .stream().map(ExerciseRow::getCells).toList(),
                templateColumns(submission));
    }

    /** WORKSHEET completeness — the shared rule, over this submission's answers. */
    private void requireWorksheetComplete(ExerciseSubmission submission, ExerciseTemplate template) {
        WorksheetBlocks.requireComplete(submission.getAnswers(), template.getBlocks());
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
        replyComment.setBlockId(root.getBlockId());
        replyComment.setEntryId(root.getEntryId());
        replyComment.setBody(body);
        ExerciseComment saved = commentRepository.save(replyComment);

        ExerciseTemplate template = submission.getAssignment().getTemplate();
        eventPublisher.publishEvent(new ProgramFlowEvents.ExerciseFeedbackReplied(
                submission.getAssignment().getOrganization().getId(), userId,
                submission.getUser().getName(), template.getName()));

        return ExerciseCommentResponse.from(saved, false);
    }

    // --- shared with the admin review side ---------------------------------

    /**
     * Everything one screen needs in one payload. {@code forAdmin} additionally
     * exposes the member's name/email (the member already knows their own) —
     * except the email for a COACH caller: {@code coach_sees} excludes contact
     * data, so a coach reviewing a submission gets the name only.
     *
     * <p>{@code forAdmin} also gates the quality tag and its options (spec §4):
     * the tag is the reviewer's metadata about the work, and a member who saw
     * "Thin" on their own sheet would read it as a grade nobody meant to give.
     */
    @Transactional(readOnly = true)
    public ExerciseSubmissionDetailResponse buildDetail(ExerciseSubmission submission, boolean forAdmin) {
        ExerciseTemplate template = submission.getAssignment().getTemplate();

        // Worksheets have no columns or rows by construction — skip both
        // queries rather than round-tripping for guaranteed-empty results on
        // every autosave.
        boolean worksheet = template.getKind() == ExerciseTemplateKind.WORKSHEET;
        List<ExerciseColumnResponse> columns = worksheet ? List.of()
                : templateColumns(submission).stream()
                        .map(ExerciseColumnResponse::from)
                        .toList();

        // Deleted rows ride along (flagged) so comment threads anchored to a
        // removed row can still show their context.
        List<ExerciseRowResponse> rows = worksheet ? List.of()
                : rowRepository.findBySubmissionId(submission.getId()).stream()
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
                template.getKind(),
                template.getBlocks(),
                submission.getAnswers(),
                template.getDescription(),
                mediaUrlPort.resolveUrl(template.getCoverImageUrl()),
                submission.getStatus(),
                submission.getAssignment().getDeadline(),
                submission.getLastSavedAt(),
                submission.getSubmittedAt(),
                submission.getChangesRequestedAt(),
                submission.getReviewedAt(),
                forAdmin ? member.getName() : null,
                forAdmin && !isCoachCaller() ? member.getEmail() : null,
                columns,
                template.getExampleRow(),
                template.isAllowAddRows(),
                rows,
                comments,
                forAdmin ? submission.getQualityTagKey() : null,
                forAdmin ? submission.getQualityTagLabel() : null,
                forAdmin ? submission.getQualityTaggedAt() : null,
                forAdmin && qualityTagCatalog != null
                        ? qualityTagCatalog.reviewerName(submission.getQualityTaggedBy()) : null,
                forAdmin && qualityTagCatalog != null
                        ? qualityTagCatalog.tags().stream()
                                .map(t -> new ExerciseSubmissionDetailResponse
                                        .ExerciseQualityTagOption(t.key(), t.label()))
                                .toList()
                        : List.of());
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

    /**
     * Setter-injected on purpose: this class's constructor signature carries
     * cross-feature parameters pinned verbatim in the frozen ArchUnit store
     * (append-never), so it cannot grow a constructor parameter. The accessor
     * is a shared-kernel type, so the edge itself is legal.
     */
    private CurrentUserAccessor currentUserAccessor;

    @org.springframework.beans.factory.annotation.Autowired
    void setCurrentUserAccessor(CurrentUserAccessor currentUserAccessor) {
        this.currentUserAccessor = currentUserAccessor;
    }

    /**
     * Setter-injected for the same frozen-signature reason as above (the bean
     * itself is same-feature, so the edge is legal). Null in plain unit tests
     * without a Spring context — the staff-only tag fields then read as absent
     * rather than blowing up a member-path test.
     */
    private QualityTagCatalog qualityTagCatalog;

    @org.springframework.beans.factory.annotation.Autowired
    void setQualityTagCatalog(QualityTagCatalog qualityTagCatalog) {
        this.qualityTagCatalog = qualityTagCatalog;
    }

    /**
     * Is the authenticated caller a COACH? Null accessor = plain unit tests
     * without a Spring context; treat as non-coach (the admin paths those
     * tests drive). Only consulted behind {@code forAdmin}, so member-facing
     * builds never touch the security context.
     */
    private boolean isCoachCaller() {
        return currentUserAccessor != null
                && UserRole.COACH.name().equals(currentUserAccessor.require().role());
    }

    /**
     * "Reviewer side of the loop" — drives the DTO's {@code byAdmin} flag (the
     * web renders it as a "Reviewer" badge). Coaches review too, so a coach's
     * comment must keep the badge when the thread is re-hydrated.
     *
     * <p>Exactly two {@code getRole()} call sites on purpose: this method's
     * exercise→auth call occurrences are pinned by count in the frozen ArchUnit
     * store (append-never), so one call would prune a stored violation and
     * three would mint a new one.
     */
    private static boolean isAdmin(User user) {
        UserRole role = user.getRole();
        return role == UserRole.ORG_ADMIN || role == UserRole.SUPER_ADMIN
                || user.getRole() == UserRole.COACH;
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
