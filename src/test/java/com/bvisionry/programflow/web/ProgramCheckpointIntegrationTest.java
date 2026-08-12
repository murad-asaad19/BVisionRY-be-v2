package com.bvisionry.programflow.web;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.dto.AssignOrgRequest;
import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.CreateModuleRequest;
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.MoveTaskRequest;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateAudienceRequest;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateModuleRequest;
import com.bvisionry.programflow.dto.UpdateTaskRequest;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The board's checkpoint + revert safety net (V174): capture on open, restore
 * on demand, and the guard that stops a revert from quietly deleting work
 * members already did.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ProgramCheckpointIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private ProgramAdminService adminService;
    @Autowired private ProgramCheckpointService checkpointService;
    @Autowired private ProgramSubmissionRepository submissions;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Organization org;
    private User member;
    private UUID cohortId;
    private UUID moduleId;
    private UUID keptTaskId;
    private UUID doomedTaskId;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Checkpoint Org");
        org.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        org.setActive(true);
        org = orgs.saveAndFlush(org);
        member = TestAuthentication.authenticateAsMember(users, org);
        TestAuthentication.authenticateAsSuperAdmin(users);

        cohortId = cohortService.create(new CreateCohortRequest("Checkpoint Cohort")).id();
        cohortService.assignOrg(cohortId, new AssignOrgRequest(org.getId(), false, List.of(), false));
        cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId())));

        moduleId = adminService.createModule(cohortId,
                new CreateModuleRequest("Week 1", "Kick-off", "Mindset")).id();
        keptTaskId = adminService.createTask(cohortId, moduleId, null).id();
        doomedTaskId = adminService.createTask(cohortId, moduleId, null).id();
        adminService.updateTask(cohortId, keptTaskId, taskSave("Kept task", ProgramTaskStatus.LIVE,
                List.of(new FieldUpsert(null, com.bvisionry.programflow.domain.FieldType.SHORT,
                        true, java.util.Map.of("question", "Why?")))));
        adminService.updateTask(cohortId, doomedTaskId,
                taskSave("Doomed task", ProgramTaskStatus.LIVE, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static UpdateTaskRequest taskSave(String name, ProgramTaskStatus status,
            List<FieldUpsert> fields) {
        return new UpdateTaskRequest(name, null, status, false, null, null, null, fields);
    }

    /** Reads the board fresh — revert writes in raw SQL, so never trust the 1st-level cache. */
    private BoardResponse board() {
        entityManager.flush();
        entityManager.clear();
        return adminService.getBoard(cohortId);
    }

    /* ------------------------------------------------------- capture + revert */

    @Test
    void revert_undoesEveryKindOfEditMadeSinceTheCheckpoint() {
        checkpointService.capture(cohortId);

        // ADD a module (with a task), EDIT a task, MOVE one, DELETE one,
        // narrow the audience, rename the module.
        UUID addedModuleId = adminService.createModule(cohortId,
                new CreateModuleRequest("Week 2", null, null)).id();
        UUID addedTaskId = adminService.createTask(cohortId, addedModuleId, null).id();
        adminService.updateTask(cohortId, addedTaskId,
                taskSave("Added task", ProgramTaskStatus.DRAFT, List.of()));
        adminService.updateTask(cohortId, keptTaskId,
                taskSave("Renamed on a whim", ProgramTaskStatus.DRAFT, List.of()));
        adminService.moveTask(cohortId, keptTaskId, new MoveTaskRequest(addedModuleId, 0));
        adminService.deleteTask(cohortId, doomedTaskId);
        adminService.updateModule(cohortId, moduleId,
                new UpdateModuleRequest("Week 1 (edited)", null, null, ModuleLockMode.UNLOCKED, null));
        adminService.updateAudience(cohortId, moduleId,
                new UpdateAudienceRequest(AudienceMode.MEMBERS, List.of()));

        assertThat(board().modules()).hasSize(2);

        checkpointService.revert(cohortId, false);

        BoardResponse after = board();
        assertThat(after.modules()).as("the module added since is gone").hasSize(1);
        ModuleDto restored = after.modules().get(0);
        assertThat(restored.id()).isEqualTo(moduleId);
        assertThat(restored.name()).isEqualTo("Week 1");
        assertThat(restored.summary()).isEqualTo("Kick-off");
        assertThat(restored.pillarLabel()).isEqualTo("Mindset");
        assertThat(restored.lockMode()).isEqualTo(ModuleLockMode.SEQUENTIAL);
        assertThat(restored.audience().mode()).isEqualTo(AudienceMode.ALL);

        assertThat(restored.tasks()).extracting(TaskDto::id)
                .as("the moved task came home; the deleted one came back under its OWN id")
                .containsExactly(keptTaskId, doomedTaskId);
        assertThat(restored.tasks()).extracting(TaskDto::position)
                .as("positions re-derived from list order — no gaps for createTask to collide with")
                .containsExactly(0, 1);
        TaskDto kept = restored.tasks().get(0);
        assertThat(kept.name()).isEqualTo("Kept task");
        assertThat(kept.status()).isEqualTo(ProgramTaskStatus.LIVE);
        assertThat(kept.fields()).singleElement()
                .extracting(com.bvisionry.programflow.dto.FieldDto::type)
                .isEqualTo(com.bvisionry.programflow.domain.FieldType.SHORT);
        assertThat(restored.tasks().get(1).name()).isEqualTo("Doomed task");
        assertThat(after.stats().tasks()).isEqualTo(2);
    }

    /**
     * The checkpoint is a SNAPSHOT of the moment the board was opened — it must
     * not track the live board. A board read is exactly what a TanStack refetch
     * does on every mutation, and if that re-captured, Revert would no-op.
     */
    @Test
    void boardReadsNeverMoveTheCheckpoint() {
        checkpointService.capture(cohortId);
        adminService.updateTask(cohortId, keptTaskId,
                taskSave("Edited", ProgramTaskStatus.DRAFT, List.of()));

        adminService.getBoard(cohortId);
        adminService.getBoard(cohortId);

        checkpointService.revert(cohortId, false);
        assertThat(board().modules().get(0).tasks()).extracting(TaskDto::name)
                .containsExactly("Kept task", "Doomed task");
    }

    /** Re-opening the board starts a NEW session: the snapshot moves forward. */
    @Test
    void captureReplacesTheCallersPreviousCheckpoint() {
        checkpointService.capture(cohortId);
        adminService.updateTask(cohortId, keptTaskId,
                taskSave("Second-session baseline", ProgramTaskStatus.DRAFT, List.of()));
        checkpointService.capture(cohortId);

        adminService.updateTask(cohortId, keptTaskId,
                taskSave("Throwaway", ProgramTaskStatus.DRAFT, List.of()));
        checkpointService.revert(cohortId, false);

        assertThat(board().modules().get(0).tasks().get(0).name())
                .isEqualTo("Second-session baseline");
        assertThat(checkpointService.find(cohortId)).as("the net survives a revert").isPresent();
    }

    /**
     * The empty snapshot: a curriculum built from nothing since the checkpoint
     * reverts back to nothing. Worth its own case because every "keep" set is
     * empty here, which is exactly where a naive {@code NOT IN ()} would blow up.
     */
    @Test
    void revert_toAnEmptyBoard_clearsTheWholeCurriculum() {
        adminService.deleteModule(cohortId, moduleId);
        checkpointService.capture(cohortId);

        UUID freshModuleId = adminService.createModule(cohortId,
                new CreateModuleRequest("All new", null, null)).id();
        adminService.createTask(cohortId, freshModuleId, null);

        checkpointService.revert(cohortId, false);
        assertThat(board().modules()).isEmpty();
        assertThat(board().stats().tasks()).isZero();
    }

    /* --------------------------------------------------------- member work 409 */

    @Test
    void revert_refusesToDestroyMemberWork_untilForced() {
        checkpointService.capture(cohortId);

        UUID freshTaskId = adminService.createTask(cohortId, moduleId, null).id();
        adminService.updateTask(cohortId, freshTaskId,
                taskSave("Member started this", ProgramTaskStatus.LIVE, List.of()));
        ProgramSubmission work = new ProgramSubmission();
        work.setTaskId(freshTaskId);
        work.setUserId(member.getId());
        submissions.saveAndFlush(work);

        assertThatThrownBy(() -> checkpointService.revert(cohortId, false))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Member started this")
                .hasMessageContaining("1 member");
        assertThat(board().modules().get(0).tasks()).as("the refusal changed nothing").hasSize(3);

        checkpointService.revert(cohortId, true);
        assertThat(board().modules().get(0).tasks()).extracting(TaskDto::id)
                .containsExactly(keptTaskId, doomedTaskId);
        assertThat(submissions.findByTaskIdIn(List.of(freshTaskId))).isEmpty();
    }

    /**
     * A task that existed at checkpoint time keeps its member work through a
     * revert — including when the admin dragged it into a module created since,
     * which the module's ON DELETE CASCADE would otherwise have swallowed.
     */
    @Test
    void revert_keepsMemberWorkOnTasksTheSnapshotHolds() {
        ProgramSubmission work = new ProgramSubmission();
        work.setTaskId(keptTaskId);
        work.setUserId(member.getId());
        submissions.saveAndFlush(work);
        checkpointService.capture(cohortId);

        UUID addedModuleId = adminService.createModule(cohortId,
                new CreateModuleRequest("Scratch", null, null)).id();
        adminService.moveTask(cohortId, keptTaskId, new MoveTaskRequest(addedModuleId, 0));

        assertThatCode(() -> checkpointService.revert(cohortId, false)).doesNotThrowAnyException();
        assertThat(submissions.findByTaskIdIn(List.of(keptTaskId)))
                .as("no 409 was needed, and nothing was lost").hasSize(1);
        assertThat(board().modules()).singleElement()
                .extracting(ModuleDto::id).isEqualTo(moduleId);
    }

    /* ---------------------------------------------------------------- archived */

    @Test
    void revert_refusesOnAnArchivedCohort() {
        checkpointService.capture(cohortId);
        adminService.deleteTask(cohortId, doomedTaskId);
        cohortService.archive(cohortId);

        assertThatThrownBy(() -> checkpointService.revert(cohortId, false))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("archived");
    }
}
