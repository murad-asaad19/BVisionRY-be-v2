package com.bvisionry.evaluation;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.evaluation.dto.PillarUnlockSummary;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.OverallSummaryHistory;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.evaluation.entity.PillarEvaluationHistory;
import com.bvisionry.evaluation.entity.SubmissionPillarUnlock;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin-driven selective re-edit of an evaluated submission. Handles the
 * unlock / relock lifecycle (status transitions + audit) and the snapshot
 * archival used by the partial re-evaluation path in {@link EvaluationService}.
 *
 * <p>Lifecycle for a submission:
 * <pre>
 *   EVALUATED ── unlockPillars ─▶ PENDING_REEDIT ── member edits unlocked pillars
 *                                       │                  │
 *                                       │                  └── submit ─▶ SUBMITTED ─▶ EVALUATED
 *                                       │                                   (partial re-eval)
 *                                       └── relockPillars (admin cancels) ─▶ EVALUATED
 * </pre>
 *
 * <p>Snapshot tables ({@code pillar_evaluation_history},
 * {@code overall_summary_history}) preserve the prior eval state — they are
 * written from {@link #archiveEvaluation} right before
 * the partial re-eval overwrites the live rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PillarReeditService {

    public static final String ARCHIVE_REASON_PILLAR_REEVAL = "PILLAR_REEVAL";
    public static final String ARCHIVE_REASON_FULL_REEVAL = "FULL_REEVAL";
    public static final String ARCHIVE_REASON_DEGRADED_RETRY = "DEGRADED_RETRY";

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final SubmissionPillarUnlockRepository unlockRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final PillarEvaluationHistoryRepository pillarEvaluationHistoryRepository;
    private final OverallSummaryHistoryRepository overallSummaryHistoryRepository;
    private final AuditService auditService;

    /**
     * Unlock the listed pillars on an evaluated submission so the member can
     * re-edit those questions. Idempotent on already-unlocked pillars (existing
     * unlock rows are kept; the reason is updated when supplied).
     *
     * @param orgId      Owning org — enforced for tenant scoping; the controller
     *                   already gates by {@code @PreAuthorize}.
     * @param assignmentId Assignment whose submission to unlock pillars on.
     * @param pillarIds  Pillars to open for editing. Must all belong to the
     *                   submission's pipeline; PERSONAL pillars are rejected
     *                   because they don't carry an evaluation to redo.
     * @param reason     Optional admin-supplied reason persisted on the unlock row.
     */
    @Transactional
    public PillarUnlockSummary unlockPillars(UUID orgId, UUID assignmentId,
                                             List<UUID> pillarIds, String reason) {
        if (pillarIds == null || pillarIds.isEmpty()) {
            throw new BadRequestException("At least one pillar must be selected to unlock.");
        }

        Submission submission = loadSubmissionForOrg(orgId, assignmentId);
        if (submission.getStatus() != SubmissionStatus.EVALUATED
                && submission.getStatus() != SubmissionStatus.PENDING_REEDIT) {
            throw new BadRequestException(
                    "Only evaluated submissions can have pillars unlocked (was "
                            + submission.getStatus() + ").");
        }

        Pipeline pipeline = submission.getAssignment().getPipeline();
        Map<UUID, Pillar> pipelinePillars = pipeline.getPillars().stream()
                .collect(Collectors.toMap(Pillar::getId, p -> p));

        // Reject IDs that don't belong to this pipeline up-front so a typo can't
        // create dangling unlock rows. PERSONAL pillars carry profile data, not
        // an evaluation — re-editing them has no AI step to re-run.
        Set<UUID> requested = Set.copyOf(pillarIds);
        for (UUID pid : requested) {
            Pillar p = pipelinePillars.get(pid);
            if (p == null) {
                throw new BadRequestException(
                        "Pillar " + pid + " does not belong to this pipeline.");
            }
            if (p.getType() == com.bvisionry.common.enums.PillarType.PERSONAL) {
                throw new BadRequestException(
                        "Personal pillars can't be unlocked for re-evaluation.");
            }
        }

        UUID adminId = SecurityUtils.getCurrentUserId();

        Set<UUID> alreadyUnlocked = Set.copyOf(unlockRepository.findUnlockedPillarIds(submission.getId()));
        List<UUID> newlyUnlocked = requested.stream()
                .filter(id -> !alreadyUnlocked.contains(id))
                .toList();

        String trimmedReason = trimToNull(reason);
        Instant now = Instant.now();
        List<SubmissionPillarUnlock> rowsToInsert = newlyUnlocked.stream()
                .map(pid -> {
                    SubmissionPillarUnlock row = new SubmissionPillarUnlock();
                    row.setSubmission(submission);
                    row.setPillar(pipelinePillars.get(pid));
                    row.setUnlockedByAdminId(adminId);
                    row.setReason(trimmedReason);
                    row.setUnlockedAt(now);
                    return row;
                })
                .toList();
        unlockRepository.saveAll(rowsToInsert);

        if (submission.getStatus() == SubmissionStatus.EVALUATED) {
            submission.setStatus(SubmissionStatus.PENDING_REEDIT);
            submissionRepository.save(submission);
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("pipelineName", pipeline.getName());
        auditDetails.put("pillarIds", newlyUnlocked.stream().map(UUID::toString).toList());
        auditDetails.put("pillarNames", newlyUnlocked.stream()
                .map(id -> pipelinePillars.get(id).getName()).toList());
        if (trimmedReason != null) {
            auditDetails.put("reason", trimmedReason);
        }
        auditService.log(adminId, orgId, OrgAuditActions.ASSESSMENT_PILLARS_UNLOCKED,
                OrgAuditActions.ENTITY_SUBMISSION, submission.getId(), auditDetails);

        log.info("Admin {} unlocked {} pillar(s) on submission {} (newly unlocked: {})",
                adminId, requested.size(), submission.getId(), newlyUnlocked.size());

        return summarize(submission);
    }

    /**
     * Cancel an unlock window before the member re-submits. Removes all unlock
     * rows for the submission and flips PENDING_REEDIT → EVALUATED. Existing
     * pillar evaluations and the overall summary are untouched (they were never
     * replaced), so the member's view returns to read-only on the previous
     * results.
     *
     * <p>Any answer edits the member made during the unlock window remain
     * persisted but become invisible to the eval pipeline (status is no longer
     * editable, and on the next unlock they'll just be the starting point).
     */
    @Transactional
    public PillarUnlockSummary relockPillars(UUID orgId, UUID assignmentId) {
        Submission submission = loadSubmissionForOrg(orgId, assignmentId);
        if (submission.getStatus() != SubmissionStatus.PENDING_REEDIT) {
            throw new BadRequestException(
                    "Submission is not currently in re-edit mode (status was "
                            + submission.getStatus() + ").");
        }

        int relockedCount = unlockRepository.deleteBySubmissionId(submission.getId());

        submission.setStatus(SubmissionStatus.EVALUATED);
        submissionRepository.save(submission);

        UUID adminId = SecurityUtils.getCurrentUserId();
        auditService.log(adminId, orgId, OrgAuditActions.ASSESSMENT_PILLARS_RELOCKED,
                OrgAuditActions.ENTITY_SUBMISSION, submission.getId(),
                Map.of(
                        "pipelineName", submission.getAssignment().getPipeline().getName(),
                        "relockedPillarCount", relockedCount));

        log.info("Admin {} re-locked {} pillar(s) on submission {}",
                adminId, relockedCount, submission.getId());

        return summarize(submission);
    }

    /**
     * Read the current unlock state for an assignment — feeds the admin drawer
     * so it can show which pillars are currently editable plus the
     * unlocked-by/at metadata. Read-only.
     */
    @Transactional(readOnly = true)
    public PillarUnlockSummary getUnlockSummary(UUID orgId, UUID assignmentId) {
        Submission submission = loadSubmissionForOrg(orgId, assignmentId);
        return summarize(submission);
    }

    /**
     * Resolve the unlocked pillar IDs for a submission — surfaced on member
     * GET endpoints so the frontend can render only the editable pillars.
     */
    @Transactional(readOnly = true)
    public List<UUID> findUnlockedPillarIds(UUID submissionId) {
        return unlockRepository.findUnlockedPillarIds(submissionId);
    }

    /**
     * Snapshot the supplied live evaluation rows into the history tables before a
     * re-evaluation overwrites them in place. {@code scope == null} snapshots every
     * supplied pillar (a full re-evaluation); a non-null set snapshots only those
     * pillars (a partial re-evaluation). The overall summary is always snapshotted
     * because any re-eval regenerates it.
     *
     * <p>The live rows are NOT deleted — both re-eval paths upsert them in place. The
     * caller loads the rows once, passes them here, and reuses them for the upsert, so
     * the snapshot and the overwrite run in ONE transaction (the caller's persist
     * step). An AI run that crashed before persist therefore leaves the prior results
     * fully intact. No-op on a first evaluation (nothing to snapshot).
     *
     * @param submissionId   Owning submission — used to version the history rows.
     * @param currentEvals   The live pillar_evaluation rows (already loaded).
     * @param currentSummary The live overall_summary row, or {@code null} if none.
     * @param scope          Pillar IDs to snapshot, or {@code null} for all of them.
     * @param reason         Archive-reason constant persisted on the snapshot rows.
     */
    @Transactional
    public void archiveEvaluation(UUID submissionId, List<PillarEvaluation> currentEvals,
                                  OverallSummary currentSummary, Set<UUID> scope, String reason) {
        Instant archivedAt = Instant.now();
        UUID adminId = currentAdminIdOrNull();
        snapshotPillars(submissionId, currentEvals, scope, reason, archivedAt, adminId);
        snapshotSummary(submissionId, currentSummary, reason, archivedAt, adminId);
    }

    /** Snapshot the in-scope pillar evaluations to history, versioned per pillar. */
    private void snapshotPillars(UUID submissionId, List<PillarEvaluation> currentEvals,
                                 Set<UUID> scope, String reason, Instant archivedAt, UUID adminId) {
        List<PillarEvaluation> toSnapshot = currentEvals.stream()
                .filter(pe -> pe.getPillar() != null)
                .filter(pe -> scope == null || scope.contains(pe.getPillar().getId()))
                .toList();
        if (toSnapshot.isEmpty()) {
            return;
        }
        Set<UUID> pillarIds = toSnapshot.stream()
                .map(pe -> pe.getPillar().getId())
                .collect(Collectors.toSet());
        Map<UUID, Integer> baseVersionByPillar = pillarEvaluationHistoryRepository
                .findMaxVersionsByPillarIds(submissionId, pillarIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> ((Number) row[1]).intValue()));

        List<PillarEvaluationHistory> snaps = new ArrayList<>(toSnapshot.size());
        for (PillarEvaluation pe : toSnapshot) {
            int next = baseVersionByPillar.getOrDefault(pe.getPillar().getId(), 0) + 1;
            PillarEvaluationHistory snap = PillarEvaluationHistory.fromLive(pe);
            snap.setVersionNumber(next);
            snap.setArchivedAt(archivedAt);
            snap.setArchivedReason(reason);
            snap.setArchivedByAdminId(adminId);
            snaps.add(snap);
        }
        pillarEvaluationHistoryRepository.saveAll(snaps);
    }

    /** Snapshot the overall summary to history, versioned per submission. */
    private void snapshotSummary(UUID submissionId, OverallSummary currentSummary,
                                 String reason, Instant archivedAt, UUID adminId) {
        if (currentSummary == null) {
            return;
        }
        int nextV = overallSummaryHistoryRepository.findMaxVersion(submissionId) + 1;
        OverallSummaryHistory snap = OverallSummaryHistory.fromLive(currentSummary);
        snap.setVersionNumber(nextV);
        snap.setArchivedAt(archivedAt);
        snap.setArchivedReason(reason);
        snap.setArchivedByAdminId(adminId);
        overallSummaryHistoryRepository.save(snap);
    }

    /**
     * Clear the unlock rows for a submission once a partial re-eval has
     * succeeded. Called by {@code EvaluationService.evaluateSubmission} after
     * the new pillar evaluations have been written, in the same transaction
     * so a failure leaves the unlock state intact (so the admin/member can
     * see the eval failed and the reedit window is still open).
     */
    @Transactional
    public void clearUnlocks(UUID submissionId) {
        unlockRepository.deleteBySubmissionId(submissionId);
    }

    // -------------------- helpers --------------------

    private Submission loadSubmissionForOrg(UUID orgId, UUID assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId.toString()));
        if (!assignment.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Assignment", assignmentId.toString());
        }
        // Multi-attempt assignments produce N submissions per (assignment, user)
        // — pillar unlocks always target the latest attempt, since older ones
        // are read-only history and unlocking them would have no member-visible
        // effect.
        return submissionRepository.requireLatestForAssignment(
                assignment, "No submission exists for this assignment.");
    }

    private PillarUnlockSummary summarize(Submission submission) {
        List<SubmissionPillarUnlock> unlocks = unlockRepository.findBySubmissionId(submission.getId());
        List<PillarUnlockSummary.UnlockedPillar> entries = unlocks.stream()
                .map(u -> new PillarUnlockSummary.UnlockedPillar(
                        u.getPillar().getId(),
                        u.getPillar().getName(),
                        u.getUnlockedAt(),
                        u.getUnlockedByAdminId(),
                        u.getReason()))
                .toList();
        return new PillarUnlockSummary(
                submission.getId(),
                submission.getStatus(),
                entries);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static UUID currentAdminIdOrNull() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            // Called from an async re-eval thread without a security context.
            return null;
        }
    }
}
