package com.bvisionry.programflow.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
import com.bvisionry.programflow.dto.FieldDto;
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.SaveBoardRequest;
import com.bvisionry.programflow.dto.SaveBoardRequest.ModuleUpsert;
import com.bvisionry.programflow.dto.SaveBoardRequest.TaskUpsert;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.BoardPayloads;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Curriculum builder's whole-board Save — since §13.10 the ONLY writer of a
 * cohort's curriculum. One call creates, updates, moves and deletes at once,
 * holds every typed-spine rule, refuses to bin member work unasked, and refuses
 * outright when the board moved underneath the admin.
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

        // The first save of a new cohort: ids minted here, exactly as the
        // browser mints them for a module or task the admin just dropped in.
        moduleId = UUID.randomUUID();
        keptTaskId = UUID.randomUUID();
        doomedTaskId = UUID.randomUUID();
        adminService.saveBoard(cohortId, new SaveBoardRequest(0L, false, List.of(
                new ModuleUpsert(moduleId, "Week 1", "Kick-off", "Mindset", true,
                        ModuleLockMode.SEQUENTIAL, null, AudienceMode.ALL, List.of(), List.of(
                                task(keptTaskId, "Kept task", ProgramTaskStatus.LIVE),
                                task(doomedTaskId, "Doomed task", ProgramTaskStatus.LIVE))))));
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

    private static TaskUpsert task(UUID id, String name, ProgramTaskStatus status) {
        return new TaskUpsert(id, name, null, status, false,
                ProgramTaskType.LESSON, null, null, List.of());
    }

    private static ModuleUpsert module(UUID id, String name, List<TaskUpsert> tasks) {
        return new ModuleUpsert(id, name, null, null, true, ModuleLockMode.UNLOCKED, null,
                AudienceMode.ALL, List.of(), tasks);
    }

    private TaskDto taskOf(BoardResponse board, UUID taskId) {
        return board.modules().stream().flatMap(m -> m.tasks().stream())
                .filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
    }

    private void workOn(UUID taskId) {
        ProgramSubmission work = new ProgramSubmission();
        work.setTaskId(taskId);
        work.setUserId(member.getId());
        submissions.saveAndFlush(work);
    }

    /* ---------------------------------------------------------- the one write */

    /**
     * One Save doing everything an editing session does: rename a module,
     * retarget its audience, delete a task, MOVE another into a module created
     * by the same payload, and add a task with a browser-minted field id.
     *
     * <p>The moved task carries member work, which is the ordering guarantee:
     * upserts run before deletes, so it is re-parented before its old module is
     * dropped and never falls through that module's ON DELETE CASCADE.
     */
    @Test
    void save_appliesCreatesUpdatesMovesAndDeletesInOneCall() {
        workOn(keptTaskId);
        BoardResponse before = adminService.getBoard(cohortId);
        ModuleDto existing = before.modules().get(0);
        UUID newModuleId = UUID.randomUUID();
        UUID newTaskId = UUID.randomUUID();
        UUID newFieldId = UUID.randomUUID();

        BoardResponse after = adminService.saveBoard(cohortId,
                new SaveBoardRequest(before.version(), true, List.of(
                // Week 1 renamed, narrowed to one member, emptied: one task moved
                // out and the other dropped entirely.
                new ModuleUpsert(existing.id(), "Week 1 (renamed)", "New summary", "Growth", true,
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
        assertThat(after.version()).as("a save moves the board version on")
                .isEqualTo(before.version() + 1);

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
        assertThat(submissions.findByTaskIdIn(List.of(keptTaskId)))
                .as("the moved task kept its member work through its old module's delete")
                .hasSize(1);
    }

    /** A field with no id is still ours to create — the drawer's "add a field" path. */
    @Test
    void save_generatesIdsForFieldsThatArriveWithoutOne() {
        BoardResponse before = adminService.getBoard(cohortId);
        adminService.saveBoard(cohortId, BoardPayloads.edit(before, keptTaskId,
                t -> new TaskUpsert(t.id(), t.name(), t.dueDate(), t.status(), t.aiDraft(),
                        t.taskType(), t.refId(), t.milestoneRole(),
                        List.of(new FieldUpsert(null, FieldType.LONG, true,
                                Map.of("question", "Why?"))))));

        assertThat(taskOf(board(), keptTaskId).fields()).singleElement()
                .satisfies(f -> {
                    assertThat(f.id()).isNotNull();
                    assertThat(f.type()).isEqualTo(FieldType.LONG);
                });
    }

    /* ---------------------------------------------------------- stale version */

    /**
     * The payload is a COMPLETE board, so a save built on a board someone else
     * has since changed does not merge — it deletes their work. Refused with a
     * 412; the builder reloads.
     */
    @Test
    void save_refusesABoardBuiltOnAStaleVersion() {
        BoardResponse stale = adminService.getBoard(cohortId);

        // Another admin adds a module and saves first.
        adminService.saveBoard(cohortId, BoardPayloads.of(board(),
                List.of(module(UUID.randomUUID(), "Theirs", List.of()))));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.echo(stale)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED))
                .hasMessageContaining("Someone else saved this board");
        assertThat(board().modules()).extracting(ModuleDto::name)
                .as("the refusal left the other admin's board alone").containsExactly("Theirs");
    }

    /** Re-reading picks the new version up, and the same edits then save cleanly. */
    @Test
    void save_succeedsOnceTheBoardIsReloaded() {
        adminService.saveBoard(cohortId, BoardPayloads.echo(adminService.getBoard(cohortId)));

        assertThatCode(() -> adminService.saveBoard(cohortId, BoardPayloads.edit(board(),
                keptTaskId, t -> task(t.id(), "Renamed at last", t.status()))))
                .doesNotThrowAnyException();
        assertThat(taskOf(board(), keptTaskId).name()).isEqualTo("Renamed at last");
    }

    /* ------------------------------------------------------------ member work */

    @Test
    void save_refusesToDestroyMemberWork_untilForced() {
        workOn(doomedTaskId);

        BoardResponse before = adminService.getBoard(cohortId);
        ModuleDto existing = before.modules().get(0);
        // The doomed task is simply absent from the payload — a delete.
        List<ModuleUpsert> withoutDoomed = List.of(BoardPayloads.asUpsert(existing,
                List.of(BoardPayloads.asUpsert(existing.tasks().get(0)))));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId,
                new SaveBoardRequest(before.version(), false, withoutDoomed)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Doomed task")
                .hasMessageContaining("1 member")
                .hasMessageContaining("Save anyway");
        assertThat(board().modules().get(0).tasks())
                .as("the refusal changed nothing").hasSize(2);

        // The refusal wrote nothing, so the version the admin holds is still
        // current — "Save anyway" is the same payload with force set.
        adminService.saveBoard(cohortId,
                new SaveBoardRequest(before.version(), true, withoutDoomed));
        assertThat(board().modules().get(0).tasks()).extracting(TaskDto::id)
                .containsExactly(keptTaskId);
        assertThat(submissions.findByTaskIdIn(List.of(doomedTaskId))).isEmpty();
    }

    /**
     * Retyping is deleting in disguise: {@code task_type} decides how a
     * member's completion is judged and which fields survive the save, so a
     * LESSON with submissions turned COURSE silently reverts everyone's
     * progress even though the task id survives. Same guard as a deletion —
     * refused without force, the 409 naming the task and its members.
     */
    @Test
    void save_refusesToRetypeATaskWithMemberWork_untilForced() {
        workOn(keptTaskId);

        // A same-type edit needs no force — the guard fires on the retype,
        // not on the mere existence of work.
        adminService.saveBoard(cohortId, BoardPayloads.edit(board(), keptTaskId,
                t -> task(t.id(), "Still a lesson", t.status())));

        SaveBoardRequest retype = BoardPayloads.edit(board(), keptTaskId,
                t -> new TaskUpsert(t.id(), "Now a course", null, ProgramTaskStatus.DRAFT,
                        false, ProgramTaskType.COURSE, null, null, List.of()));
        assertThatThrownBy(() -> adminService.saveBoard(cohortId, retype))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Still a lesson")
                .hasMessageContaining("1 member")
                .hasMessageContaining("Save anyway");
        assertThat(taskOf(board(), keptTaskId).taskType())
                .as("the refusal changed nothing").isEqualTo(ProgramTaskType.LESSON);

        // The refusal wrote nothing, so "Save anyway" is the same payload
        // with force set — and the retype then applies.
        adminService.saveBoard(cohortId,
                new SaveBoardRequest(retype.expectedVersion(), true, retype.modules()));
        assertThat(taskOf(board(), keptTaskId).taskType()).isEqualTo(ProgramTaskType.COURSE);
        assertThat(submissions.findByTaskIdIn(List.of(keptTaskId)))
                .as("a forced retype keeps the rows; it is the MEANING that reverts")
                .hasSize(1);
    }

    /* -------------------------------------------------------- spine validation */

    @Test
    void save_rejectsATaskTheTypedSpineForbids() {
        SaveBoardRequest req = BoardPayloads.edit(adminService.getBoard(cohortId), keptTaskId,
                t -> new TaskUpsert(t.id(), "Unlinked course", null, ProgramTaskStatus.LIVE, false,
                        ProgramTaskType.COURSE, null, null, List.of()));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, req))
                .isInstanceOf(BadRequestException.class)
                .as("the offending task is named — a 40-task board needs to know which")
                .hasMessageContaining("Unlinked course")
                .hasMessageContaining("before publishing it");
        assertThat(taskOf(board(), keptTaskId).taskType()).isEqualTo(ProgramTaskType.LESSON);
    }

    @Test
    void save_rejectsTwoBaselineMilestonesInOnePayload() {
        BoardResponse before = adminService.getBoard(cohortId);
        SaveBoardRequest req = BoardPayloads.of(before,
                List.of(BoardPayloads.asUpsert(before.modules().get(0), List.of(
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
        BoardResponse before = adminService.getBoard(cohortId);
        adminService.saveBoard(cohortId, BoardPayloads.of(before,
                List.of(BoardPayloads.asUpsert(before.modules().get(0), List.of(
                        assessment(keptTaskId, "Baseline", MilestoneRole.BASELINE),
                        BoardPayloads.asUpsert(before.modules().get(0).tasks().get(1)))))));

        BoardResponse saved = board();
        assertThatCode(() -> adminService.saveBoard(cohortId, BoardPayloads.of(saved,
                List.of(BoardPayloads.asUpsert(saved.modules().get(0), List.of(
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

    /* ------------------------------------------------- the builder placeholder */

    /**
     * Spec §12: "Untitled module" — the name the builder seeds a new column
     * with — reached the Alumni cohort's member Journey. A module nobody named
     * fails the SAVE, so it can never get as far as a founder.
     */
    @Test
    void save_refusesAModuleStillCarryingTheBuilderPlaceholderName() {
        BoardResponse before = adminService.getBoard(cohortId);

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.of(before,
                List.of(module(UUID.randomUUID(), "Untitled module", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Untitled module")
                .hasMessageContaining("placeholder");
        assertThat(board().modules()).extracting(ModuleDto::name)
                .as("the refusal left the board alone").containsExactly("Week 1");
    }

    /**
     * The same hole one level down: the builder seeds each card with
     * "Untitled &lt;type&gt; task" and its save payload used to substitute a bare
     * "Untitled task" for a name the admin left blank, so scaffolding reached
     * the Journey looking like content.
     */
    @Test
    void save_refusesATaskStillCarryingTheBuilderPlaceholderName() {
        BoardResponse before = adminService.getBoard(cohortId);

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.of(before,
                List.of(module(UUID.randomUUID(), "Week 1", List.of(
                        task(UUID.randomUUID(), "Untitled lesson task",
                                ProgramTaskStatus.DRAFT)))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Untitled lesson task")
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.of(before,
                List.of(module(UUID.randomUUID(), "Week 1", List.of(
                        task(UUID.randomUUID(), "Untitled task",
                                ProgramTaskStatus.DRAFT)))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("placeholder");

        assertThat(board().modules()).extracting(ModuleDto::name)
                .as("the refusal left the board alone").containsExactly("Week 1");
    }

    /** A task whose type moved on keeps only the placeholder of its OWN type. */
    @Test
    void save_acceptsATaskNamedAfterAnotherTypesPlaceholder() {
        adminService.saveBoard(cohortId, BoardPayloads.of(adminService.getBoard(cohortId),
                List.of(module(UUID.randomUUID(), "Week 1", List.of(
                        task(UUID.randomUUID(), "Untitled survey task",
                                ProgramTaskStatus.DRAFT))))));

        assertThat(board().modules()).singleElement()
                .satisfies(m -> assertThat(m.tasks()).singleElement()
                        .extracting(TaskDto::name).isEqualTo("Untitled survey task"));
    }

    /** The same save, once the admin has typed a name over the placeholder. */
    @Test
    void save_acceptsTheModuleOnceItHasARealName() {
        UUID namedId = UUID.randomUUID();
        adminService.saveBoard(cohortId, BoardPayloads.of(adminService.getBoard(cohortId),
                List.of(module(namedId, "Fundraising", List.of()))));

        assertThat(board().modules()).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(namedId);
            assertThat(m.name()).isEqualTo("Fundraising");
        });
    }

    /* ------------------------------------------------------------ foreign ids */

    @Test
    void save_refusesAnIdThatBelongsToAnotherCohort() {
        UUID otherCohortId = cohortService.create(new CreateCohortRequest("Someone else")).id();
        UUID otherModuleId = UUID.randomUUID();
        adminService.saveBoard(otherCohortId, new SaveBoardRequest(0L, false,
                List.of(module(otherModuleId, "Not yours", List.of()))));

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.of(
                adminService.getBoard(cohortId),
                List.of(module(otherModuleId, "Mine now", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("another cohort");
        assertThat(adminService.getBoard(otherCohortId).modules()).singleElement()
                .extracting(ModuleDto::name).isEqualTo("Not yours");
    }

    @Test
    void save_refusesTheSameIdTwice() {
        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.of(
                adminService.getBoard(cohortId),
                List.of(module(moduleId, "Once", List.of()),
                        module(moduleId, "Twice", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("same id twice");
    }

    /** The empty board: a curriculum can be cleared, and NOT IN () must not blow up. */
    @Test
    void save_toAnEmptyBoard_clearsTheCurriculum() {
        adminService.saveBoard(cohortId, BoardPayloads.of(adminService.getBoard(cohortId),
                List.of()));
        assertThat(board().modules()).isEmpty();
        assertThat(board().stats().tasks()).isZero();
    }

    /* ---------------------------------------------------------------- archived */

    @Test
    void save_refusesOnAnArchivedCohort() {
        BoardResponse before = adminService.getBoard(cohortId);
        cohortService.archive(cohortId);

        assertThatThrownBy(() -> adminService.saveBoard(cohortId, BoardPayloads.echo(before)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("archived");
    }
}
