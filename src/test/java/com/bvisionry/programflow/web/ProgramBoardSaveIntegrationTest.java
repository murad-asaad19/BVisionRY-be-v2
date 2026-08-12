package com.bvisionry.programflow.web;

import java.util.List;
import java.util.Map;
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
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.dto.AssignOrgRequest;
import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.CreateModuleRequest;
import com.bvisionry.programflow.dto.FieldDto;
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.SaveBoardRequest;
import com.bvisionry.programflow.dto.SaveBoardRequest.ModuleUpsert;
import com.bvisionry.programflow.dto.SaveBoardRequest.TaskUpsert;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
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
 * The Curriculum builder's whole-board Save: one write that creates, updates,
 * moves and deletes at once, still holding every typed-spine rule the
 * single-task save holds, and still refusing to bin member work unasked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ProgramBoardSaveIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private ProgramAdminService adminService;
    @Autowired private ProgramSubmissionRepository submissions;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private User member;
    private UUID cohortId;
    private UUID moduleId;
    private UUID keptTaskId;
    private UUID doomedTaskId;

    @BeforeEach
    void seed() {
        Organization org = new Organization();
        org.setName("Board Save Org");
        org.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        org.setActive(true);
        org = orgs.saveAndFlush(org);
        member = TestAuthentication.authenticateAsMember(users, org);
        TestAuthentication.authenticateAsSuperAdmin(users);

        cohortId = cohortService.create(new CreateCohortRequest("Board Save Cohort")).id();
        cohortService.assignOrg(cohortId, new AssignOrgRequest(org.getId(), false, List.of(), false));
        cohortService.setOrgMembers(org.getId(), cohortId,
                new UpdateCohortMembersRequest(List.of(member.getId())));

        moduleId = adminService.createModule(cohortId,
                new CreateModuleRequest("Week 1", "Kick-off", "Mindset")).id();
        keptTaskId = adminService.createTask(cohortId, moduleId, null).id();
        doomedTaskId = adminService.createTask(cohortId, moduleId, null).id();
        adminService.updateTask(cohortId, keptTaskId,
                new UpdateTaskRequest("Kept task", null, ProgramTaskStatus.LIVE, false,
                        null, null, null, List.of()));
        adminService.updateTask(cohortId, doomedTaskId,
                new UpdateTaskRequest("Doomed task", null, ProgramTaskStatus.LIVE, false,
                        null, null, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* -------------------------------------------------------------- helpers */

    /** Reads the board fresh — the save writes in raw SQL, so never trust the 1st-level cache. */
    private BoardResponse board() {
        entityManager.flush();
        entityManager.clear();
        return adminService.getBoard(cohortId);
    }

    /** The web app's move: the board it was handed, echoed back as a save payload. */
    private static ModuleUpsert asUpsert(ModuleDto m, List<TaskUpsert> tasks) {
        return new ModuleUpsert(m.id(), m.name(), m.summary(), m.pillarLabel(),
                m.lockMode(), m.unlockAt(), m.audience().mode(), m.audience().memberIds(), tasks);
    }

    private static TaskUpsert asUpsert(TaskDto t) {
        return new TaskUpsert(t.id(), t.name(), t.dueDate(), t.status(), t.aiDraft(),
                t.taskType(), t.refId(), t.milestoneRole(),
                t.fields().stream()
                        .map(f -> new FieldUpsert(f.id(), f.type(), f.required(), f.config()))
                        .toList());
    }

    private static TaskUpsert task(UUID id, String name, ProgramTaskStatus status) {
        return new TaskUpsert(id, name, null, status, false,
                ProgramTaskType.LESSON, null, null, List.of());
    }

    private static ModuleUpsert module(UUID id, String name, List<TaskUpsert> tasks) {
        return new ModuleUpsert(id, name, null, null, ModuleLockMode.UNLOCKED, null,
                AudienceMode.ALL, List.of(), tasks);
    }

    private TaskDto taskOf(BoardResponse board, UUID taskId) {
        return board.modules().stream().flatMap(m -> m.tasks().stream())
                .filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
    }

    /* ---------------------------------------------------------- the one write */

    /**
     * One Save doing everything an editing session does: rename a module,
     * retarget its audience, delete a task, MOVE another into a module created
     * by the same payload, and add a task with a browser-minted field id.
     */
    @Test
    void save_appliesCreatesUpdatesMovesAndDeletesInOneCall() {
        ModuleDto existing = adminService.getBoard(cohortId).modules().get(0);
        UUID newModuleId = UUID.randomUUID();
        UUID newTaskId = UUID.randomUUID();
        UUID newFieldId = UUID.randomUUID();

        BoardResponse after = adminService.saveBoard(cohortId, new SaveBoardRequest(false, List.of(
                // Week 1 renamed, narrowed to one member, emptied: one task moved
                // out and the other dropped entirely.
                new ModuleUpsert(existing.id(), "Week 1 (renamed)", "New summary", "Growth",
                        ModuleLockMode.SCHEDULED, null,
                        AudienceMode.MEMBERS, List.of(member.getId()), List.of()),
                // A module that does not exist yet, holding the moved task first.
                module(newModuleId, "Week 2", List.of(
                        task(keptTaskId, "Kept task, moved", ProgramTaskStatus.LIVE),
                        new TaskUpsert(newTaskId, "Brand new", null, ProgramTaskStatus.DRAFT,
                                false, ProgramTaskType.LESSON, null, null,
                                List.of(new FieldUpsert(newFieldId, FieldType.SHORT, true,
                                        Map.of("question", "What changed?")))))))));

        assertThat(after.modules()).extracting(ModuleDto::id)
                .containsExactly(existing.id(), newModuleId);
        assertThat(after.stats().tasks()).isEqualTo(2);

        ModuleDto week1 = board().modules().get(0);
        assertThat(week1.name()).isEqualTo("Week 1 (renamed)");
        assertThat(week1.pillarLabel()).isEqualTo("Growth");
        assertThat(week1.lockMode()).isEqualTo(ModuleLockMode.SCHEDULED);
        assertThat(week1.audience().mode()).isEqualTo(AudienceMode.MEMBERS);
        assertThat(week1.audience().memberIds()).containsExactly(member.getId());
        assertThat(week1.tasks()).as("one task moved out, the other deleted").isEmpty();

        ModuleDto week2 = board().modules().get(1);
        assertThat(week2.id()).as("created under the id the browser minted").isEqualTo(newModuleId);
        assertThat(week2.tasks()).extracting(TaskDto::id).containsExactly(keptTaskId, newTaskId);
        assertThat(week2.tasks()).extracting(TaskDto::position)
                .as("positions re-derived from list order").containsExactly(0, 1);
        assertThat(week2.tasks().get(0).name()).isEqualTo("Kept task, moved");
        assertThat(week2.tasks().get(1).fields()).extracting(FieldDto::id)
                .containsExactly(newFieldId);
    }

    /** A field with no id is still ours to create — the drawer's "add a field" path. */
    @Test
    void save_generatesIdsForFieldsThatArriveWithoutOne() {
        BoardResponse before = adminService.getBoard(cohortId);
        adminService.saveBoard(cohortId, new SaveBoardRequest(false, List.of(
                asUpsert(before.modules().get(0), List.of(
                        new TaskUpsert(keptTaskId, "Kept task", null, ProgramTaskStatus.LIVE,
                                false, ProgramTaskType.LESSON, null, null,
                                List.of(new FieldUpsert(null, FieldType.LONG, true,
                                        Map.of("question", "Why?")))))))));

        assertThat(taskOf(board(), keptTaskId).fields()).singleElement()
                .satisfies(f -> {
                    assertThat(f.id()).isNotNull();
                    assertThat(f.type()).isEqualTo(FieldType.LONG);
                });
    }

    /* ------------------------------------------------------------ member work */

    @Test
    void save_refusesToDestroyMemberWork_untilForced() {
        ProgramSubmission work = new ProgramSubmission();
        work.setTaskId(doomedTaskId);
        work.setUserId(member.getId());
        submissions.saveAndFlush(work);

        ModuleDto existing = adminService.getBoard(cohortId).modules().get(0);
        // The doomed task is simply absent from the payload — a delete.
        List<ModuleUpsert> withoutDoomed = List.of(asUpsert(existing,
                List.of(asUpsert(existing.tasks().get(0)))));

        assertThatThrownBy(() ->
                adminService.saveBoard(cohortId, new SaveBoardRequest(false, withoutDoomed)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Doomed task")
                .hasMessageContaining("1 member")
                .hasMessageContaining("Save anyway");
        assertThat(board().modules().get(0).tasks())
                .as("the refusal changed nothing").hasSize(2);

        adminService.saveBoard(cohortId, new SaveBoardRequest(true, withoutDoomed));
        assertThat(board().modules().get(0).tasks()).extracting(TaskDto::id)
                .containsExactly(keptTaskId);
        assertThat(submissions.findByTaskIdIn(List.of(doomedTaskId))).isEmpty();
    }

    /* -------------------------------------------------------- spine validation */

    @Test
    void save_rejectsATaskTheTypedSpineForbids() {
        ModuleDto existing = adminService.getBoard(cohortId).modules().get(0);
        SaveBoardRequest req = new SaveBoardRequest(false, List.of(asUpsert(existing, List.of(
                new TaskUpsert(keptTaskId, "Unlinked course", null, ProgramTaskStatus.LIVE, false,
                        ProgramTaskType.COURSE, null, null, List.of())))));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, req))
                .isInstanceOf(BadRequestException.class)
                .as("the offending task is named — a 40-task board needs to know which")
                .hasMessageContaining("Unlinked course")
                .hasMessageContaining("before publishing it");
        assertThat(taskOf(board(), keptTaskId).taskType()).isEqualTo(ProgramTaskType.LESSON);
    }

    @Test
    void save_rejectsTwoBaselineMilestonesInOnePayload() {
        ModuleDto existing = adminService.getBoard(cohortId).modules().get(0);
        SaveBoardRequest req = new SaveBoardRequest(false, List.of(asUpsert(existing, List.of(
                assessment(keptTaskId, "First baseline", MilestoneRole.BASELINE),
                assessment(doomedTaskId, "Second baseline", MilestoneRole.BASELINE)))));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Second baseline")
                .hasMessageContaining("already has a baseline");
    }

    /**
     * The rule the batch had to re-derive rather than re-query: handing the
     * BASELINE role from one task to another in a SINGLE save is legal, even
     * though the DB still shows the old holder while the payload is validated.
     */
    @Test
    void save_allowsHandingAMilestoneRoleToAnotherTaskInOneCall() {
        ModuleDto existing = adminService.getBoard(cohortId).modules().get(0);
        adminService.saveBoard(cohortId, new SaveBoardRequest(false, List.of(asUpsert(existing,
                List.of(assessment(keptTaskId, "Baseline", MilestoneRole.BASELINE),
                        asUpsert(existing.tasks().get(1)))))));

        ModuleDto saved = board().modules().get(0);
        assertThatCode(() -> adminService.saveBoard(cohortId, new SaveBoardRequest(false,
                List.of(asUpsert(saved, List.of(
                        assessment(keptTaskId, "Now a check-in", MilestoneRole.CHECKIN),
                        assessment(doomedTaskId, "Now the baseline", MilestoneRole.BASELINE)))))))
                .doesNotThrowAnyException();

        assertThat(taskOf(board(), doomedTaskId).milestoneRole())
                .isEqualTo(MilestoneRole.BASELINE);
    }

    private static TaskUpsert assessment(UUID id, String name, MilestoneRole role) {
        // DRAFT + no ref: the structural rules allow an unlinked draft, which
        // keeps this case about the milestone pair and nothing else.
        return new TaskUpsert(id, name, null, ProgramTaskStatus.DRAFT, false,
                ProgramTaskType.ASSESSMENT, null, role, List.of());
    }

    /* ------------------------------------------------------------ foreign ids */

    @Test
    void save_refusesAnIdThatBelongsToAnotherCohort() {
        UUID otherCohortId = cohortService.create(new CreateCohortRequest("Someone else")).id();
        UUID otherModuleId = adminService.createModule(otherCohortId,
                new CreateModuleRequest("Not yours", null, null)).id();

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, new SaveBoardRequest(false,
                List.of(module(otherModuleId, "Mine now", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("another cohort");
        assertThat(adminService.getBoard(otherCohortId).modules()).singleElement()
                .extracting(ModuleDto::name).isEqualTo("Not yours");
    }

    @Test
    void save_refusesTheSameIdTwice() {
        assertThatThrownBy(() -> adminService.saveBoard(cohortId, new SaveBoardRequest(false,
                List.of(module(moduleId, "Once", List.of()),
                        module(moduleId, "Twice", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("same id twice");
    }

    /** The empty board: a curriculum can be cleared, and NOT IN () must not blow up. */
    @Test
    void save_toAnEmptyBoard_clearsTheCurriculum() {
        adminService.saveBoard(cohortId, new SaveBoardRequest(false, List.of()));
        assertThat(board().modules()).isEmpty();
        assertThat(board().stats().tasks()).isZero();
    }
}
