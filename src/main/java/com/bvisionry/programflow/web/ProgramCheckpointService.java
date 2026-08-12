package com.bvisionry.programflow.web;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.BoardSnapshot;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.ProgramBoardCheckpoint;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.dto.ProgramCheckpointDto;
import com.bvisionry.programflow.repository.BoardRestoreRepository;
import com.bvisionry.programflow.repository.ProgramBoardCheckpointRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The board's safety net: capture a checkpoint when an admin OPENS a cohort's
 * program board, and restore the curriculum to it on "Revert changes".
 * Autosave is untouched — every edit still lands immediately; this is the way
 * back out of an editing session the admin decided against.
 *
 * <h2>Why capture is its own endpoint and not part of {@code getBoard}</h2>
 * {@link ProgramAdminService#getBoard} is read on every render AND on every
 * TanStack Query refetch (window focus, mutation invalidation, polling).
 * Capturing there would keep re-snapshotting the LIVE board, so the checkpoint
 * would be forever identical to current state and Revert would silently do
 * nothing — the one failure mode that makes a safety net worse than none, since
 * the admin believes they have one. So capture is an explicit
 * {@code POST /checkpoint} the web app makes ONCE when the board is opened
 * (a mount effect keyed on the cohort id, guarded by a ref), never from a read.
 *
 * <p><strong>Replace, and why that is safe under React strict mode.</strong>
 * Capture overwrites the caller's existing checkpoint: "opening the board" is
 * what starts a session, and a create-if-missing rule would leave a weeks-old
 * checkpoint in place, so Revert would throw away far more than the session the
 * admin meant to discard. Strict mode's double effect invocation therefore
 * captures twice — harmlessly: no board mutation can interleave between two
 * synchronous calls of the same mount, so the second snapshot is byte-identical
 * to the first.
 *
 * <p>The checkpoint SURVIVES a revert: after restoring, the board equals the
 * snapshot again, so keeping it lets the admin start over and revert once more
 * without re-opening the page.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProgramCheckpointService {

    static final String ACTION_BOARD_REVERTED = "COHORT_BOARD_REVERTED";

    private final ProgramBoardCheckpointRepository checkpoints;
    private final ProgramModuleRepository modules;
    private final BoardRestoreRepository restore;
    private final CohortService cohortService;
    private final AuditLogger audit;
    private final CurrentUserAccessor currentUser;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    /* ------------------------------------------------------------- capture */

    /** Snapshots the curriculum as the caller's checkpoint, replacing any earlier one. */
    public ProgramCheckpointDto capture(UUID cohortId) {
        cohortService.require(cohortId);
        UUID actorId = currentUser.require().userId();
        ProgramBoardCheckpoint row = checkpoints.findByCohortIdAndCreatedBy(cohortId, actorId)
                .orElseGet(() -> {
                    ProgramBoardCheckpoint fresh = new ProgramBoardCheckpoint();
                    fresh.setCohortId(cohortId);
                    fresh.setCreatedBy(actorId);
                    return fresh;
                });
        row.setPayload(json.convertValue(snapshotOf(cohortId),
                new TypeReference<Map<String, Object>>() {}));
        row.setCreatedAt(OffsetDateTime.now());
        return new ProgramCheckpointDto(checkpoints.save(row).getCreatedAt());
    }

    /** The caller's checkpoint for this cohort, or empty when they have none. */
    @Transactional(readOnly = true)
    public Optional<ProgramCheckpointDto> find(UUID cohortId) {
        cohortService.require(cohortId);
        return checkpoints.findByCohortIdAndCreatedBy(cohortId, currentUser.require().userId())
                .map(c -> new ProgramCheckpointDto(c.getCreatedAt()));
    }

    /* -------------------------------------------------------------- revert */

    /**
     * Restores the curriculum to the caller's checkpoint. Refuses with a 409
     * when it would delete a task members have already worked on, unless
     * {@code force}.
     */
    public void revert(UUID cohortId, boolean force) {
        Cohort cohort = cohortService.requireEditable(cohortId);
        UUID actorId = currentUser.require().userId();
        ProgramBoardCheckpoint row = checkpoints.findByCohortIdAndCreatedBy(cohortId, actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkpoint", cohortId.toString()));
        BoardSnapshot snapshot = json.convertValue(row.getPayload(), BoardSnapshot.class);

        List<BoardRestoreRepository.DoomedTask> atRisk =
                restore.memberWorkAtRisk(cohortId, snapshot.taskIds());
        if (!force && !atRisk.isEmpty()) {
            throw new IllegalOperationException(memberWorkMessage(atRisk));
        }

        // The restore is raw SQL (it must insert rows under their original
        // ids). Flush first so no pending JPA write lands on top of it, clear
        // after so nothing keeps serving the pre-revert first-level cache.
        entityManager.flush();
        restore.restore(cohortId, snapshot);
        entityManager.clear();

        // Platform artifacts have no owning org, exactly like the lifecycle
        // audit in CohortService.
        audit.log(actorId, null, ACTION_BOARD_REVERTED, CohortService.ENTITY_COHORT, cohortId,
                Map.of("name", cohort.getName(),
                        "restoredAt", row.getCreatedAt().toString(),
                        "modules", snapshot.modules().size(),
                        "tasks", snapshot.taskIds().size(),
                        "discardedTasksWithMemberWork", atRisk.size(),
                        "force", force));
    }

    /* ------------------------------------------------------------- helpers */

    static String memberWorkMessage(List<BoardRestoreRepository.DoomedTask> atRisk) {
        return "Reverting would delete member work on "
                + atRisk.stream()
                        .map(t -> "“" + t.taskName() + "” (" + t.memberCount()
                                + (t.memberCount() == 1 ? " member" : " members") + ")")
                        .collect(Collectors.joining(", "))
                + ". Revert anyway to discard it.";
    }

    private BoardSnapshot snapshotOf(UUID cohortId) {
        return new BoardSnapshot(modules.findByCohortIdOrderByPositionAsc(cohortId).stream()
                .map(ProgramCheckpointService::moduleSnap)
                .toList());
    }

    private static BoardSnapshot.ModuleSnap moduleSnap(ProgramModule m) {
        return new BoardSnapshot.ModuleSnap(m.getId(), m.getName(), m.getSummary(),
                m.getPillarLabel(), m.getLockMode(), m.getUnlockAt(), m.getAssignMode(),
                List.copyOf(m.getMemberIds()),
                m.getTasks().stream().map(ProgramCheckpointService::taskSnap).toList());
    }

    private static BoardSnapshot.TaskSnap taskSnap(ProgramTask t) {
        return new BoardSnapshot.TaskSnap(t.getId(), t.getName(), t.getTaskType(), t.getRefId(),
                t.getMilestoneRole(), t.getDueDate(), t.getStatus(), t.isAiDraft(),
                t.getFields().stream()
                        .map(f -> new BoardSnapshot.FieldSnap(f.getId(), f.getFieldType(),
                                f.isRequired(), new LinkedHashMap<>(f.getConfig())))
                        .toList());
    }
}
