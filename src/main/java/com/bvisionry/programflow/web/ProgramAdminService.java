package com.bvisionry.programflow.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.FieldValidationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramSettings;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.dto.JourneyTaskState;
import com.bvisionry.programflow.dto.AudienceDto;
import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.CohortMatrixResponse;
import com.bvisionry.programflow.dto.CohortMatrixResponse.AttentionFlag;
import com.bvisionry.programflow.dto.CohortMatrixResponse.FounderRow;
import com.bvisionry.programflow.dto.CohortMatrixResponse.MilestoneCell;
import com.bvisionry.programflow.dto.CohortMatrixResponse.MilestoneColumn;
import com.bvisionry.programflow.dto.CohortMatrixResponse.ModuleCell;
import com.bvisionry.programflow.dto.CohortMatrixResponse.ModuleColumn;
import com.bvisionry.programflow.dto.CreateModuleRequest;
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.MoveTaskRequest;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.PulseResponse;
import com.bvisionry.programflow.dto.PulseResponse.CellState;
import com.bvisionry.programflow.dto.PulseResponse.PulseColumn;
import com.bvisionry.programflow.dto.PulseResponse.PulseRow;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateAudienceRequest;
import com.bvisionry.programflow.dto.UpdateModuleRequest;
import com.bvisionry.programflow.dto.UpdateTaskRequest;
import com.bvisionry.programflow.repository.CohortBoardReadRepository;
import com.bvisionry.programflow.repository.CohortMemberRow;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;

import lombok.RequiredArgsConstructor;

/** Admin (program director) operations: board, modules, tasks, pulse, settings. Spec §13: platform-scoped. */
@Service
@RequiredArgsConstructor
@Transactional
public class ProgramAdminService {

    private final ProgramModuleRepository modules;
    private final ProgramTaskRepository tasks;
    private final ProgramSubmissionRepository submissions;
    private final ProgramSettingsRepository settings;
    private final CohortRepository cohorts;
    private final CohortService cohortService;
    private final MyProgramService myProgramService;
    private final com.bvisionry.programflow.repository.TaskSpineRepository spine;
    private final CohortBoardReadRepository boardReads;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------ board

    @Transactional(readOnly = true)
    public BoardResponse getBoard(UUID cohortId) {
        List<CohortMemberRow> members = cohortFounders(cohortId);
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<ModuleDto> moduleDtos = mods.stream()
                .map(m -> ProgramMapper.toDto(m, reached(m, members)))
                .toList();
        int taskCount = mods.stream().mapToInt(m -> m.getTasks().size()).sum();
        return new BoardResponse(
                ProgramMapper.toDto(settings.findById(cohortId).orElse(null)),
                moduleDtos,
                new BoardResponse.BoardStats(mods.size(), taskCount, members.size()));
    }

    public ProgramSettingsDto updateSettings(UUID cohortId, ProgramSettingsDto req) {
        cohortService.requireEditable(cohortId);
        ProgramSettings s = settings.findById(cohortId).orElseGet(() -> {
            ProgramSettings created = new ProgramSettings();
            created.setCohortId(cohortId);
            return created;
        });
        s.setStageLabel(req.stageLabel());
        s.setDripEnabled(req.dripEnabled());
        s.setDueSoonDays(req.dueSoonDays());
        s.setEndLabel(req.endLabel());
        s.setEndAt(req.endAt());
        // Same-instrument (retake) pairs are allowed since the typed task
        // spine: the DISTANCE milestone task's submission tag — not "latest
        // evaluated" — identifies the distance submission, so an equal pair
        // can no longer self-compare.
        Map<String, String> errors = new LinkedHashMap<>();
        milestonePipelineSyncError(cohortId, MilestoneRole.BASELINE, req.baselinePipelineId())
                .ifPresent(msg -> errors.put("baselinePipelineId", msg));
        milestonePipelineSyncError(cohortId, MilestoneRole.DISTANCE, req.distancePipelineId())
                .ifPresent(msg -> errors.put("distancePipelineId", msg));
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        s.setBaselinePipelineId(req.baselinePipelineId());
        s.setDistancePipelineId(req.distancePipelineId());
        return ProgramMapper.toDto(settings.save(s));
    }

    /**
     * The designated pipeline and the cohort's milestone task must agree
     * (spec §5): a BASELINE/DISTANCE milestone task referencing pipeline X
     * while the designation says Y would tag submissions the comparison
     * could never resolve.
     */
    private java.util.Optional<String> milestonePipelineSyncError(UUID cohortId,
            MilestoneRole role, UUID designatedPipelineId) {
        if (designatedPipelineId == null) {
            return java.util.Optional.empty();
        }
        boolean mismatch = tasks.findByCohortAndMilestoneRole(cohortId, role).stream()
                .anyMatch(t -> t.getRefId() != null && !t.getRefId().equals(designatedPipelineId));
        return mismatch
                ? java.util.Optional.of("The cohort's " + role.name().toLowerCase()
                        + " milestone task references a different pipeline.")
                : java.util.Optional.empty();
    }

    // ---------------------------------------------------------------- modules

    public ModuleDto createModule(UUID cohortId, CreateModuleRequest req) {
        cohortService.requireEditable(cohortId);
        ProgramModule m = new ProgramModule();
        m.setCohortId(cohortId);
        m.setName(req.name());
        m.setSummary(req.summary());
        m.setPillarLabel(blankToNull(req.pillarLabel()));
        m.setPosition(modules.findByCohortIdOrderByPositionAsc(cohortId).size());
        return ProgramMapper.toDto(modules.save(m), reached(m, cohortFounders(cohortId)));
    }

    public ModuleDto updateModule(UUID cohortId, UUID moduleId, UpdateModuleRequest req) {
        ProgramModule m = requireEditableModule(cohortId, moduleId);
        m.setName(req.name());
        m.setSummary(req.summary());
        m.setPillarLabel(blankToNull(req.pillarLabel()));
        m.setLockMode(req.lockMode());
        m.setUnlockAt(req.unlockAt());
        return ProgramMapper.toDto(m, reached(m, cohortFounders(cohortId)));
    }

    public AudienceDto updateAudience(UUID cohortId, UUID moduleId, UpdateAudienceRequest req) {
        ProgramModule m = requireEditableModule(cohortId, moduleId);
        List<CohortMemberRow> roster = cohortFounders(cohortId);

        if (req.mode() == AudienceMode.MEMBERS) {
            var rosterIds = roster.stream().map(CohortMemberRow::getId).collect(Collectors.toSet());
            if (!rosterIds.containsAll(req.memberIds())) {
                throw new BadRequestException("One or more members are not enrolled in this cohort");
            }
        }

        var includedBefore = roster.stream()
                .filter(member -> ProgramRules.includes(m, member.getId()))
                .map(CohortMemberRow::getId)
                .collect(Collectors.toSet());

        m.setAssignMode(req.mode());
        m.setMemberIds(new LinkedHashSet<>(req.memberIds()));

        // "New module assigned" for learners the audience newly reaches — only
        // while the cohort is LAUNCHED: a DRAFT's module is invisible to
        // members and a COMPLETED cohort is read-only for them.
        var cohort = cohortService.require(cohortId);
        List<UUID> newlyAssigned = roster.stream()
                .filter(member -> !includedBefore.contains(member.getId()))
                .filter(member -> ProgramRules.includes(m, member.getId()))
                .map(CohortMemberRow::getId)
                .toList();
        if (!newlyAssigned.isEmpty()
                && cohort.getStatus() == com.bvisionry.programflow.domain.CohortStatus.LAUNCHED) {
            events.publishEvent(new ProgramFlowEvents.ModuleAssigned(
                    m.getName(), cohort.getName(), newlyAssigned));
        }

        return new AudienceDto(m.getAssignMode(),
                List.copyOf(m.getMemberIds()), reached(m, roster));
    }

    /** Deletes the module with its tasks/fields/submissions (DB cascades). */
    public void deleteModule(UUID cohortId, UUID moduleId) {
        ProgramModule m = requireEditableModule(cohortId, moduleId);
        modules.delete(m);
        // Compact positions so createModule's size-based position stays unique.
        int position = 0;
        for (ProgramModule other : modules.findByCohortIdOrderByPositionAsc(cohortId)) {
            if (!other.getId().equals(moduleId)) {
                other.setPosition(position++);
            }
        }
    }

    // ------------------------------------------------------------------ tasks

    public TaskDto createTask(UUID cohortId, UUID moduleId, ProgramTaskType taskType) {
        ProgramModule m = requireEditableModule(cohortId, moduleId);
        ProgramTaskType type = taskType == null ? ProgramTaskType.LESSON : taskType;
        ProgramTask t = new ProgramTask();
        t.setModule(m);
        t.setTaskType(type);
        t.setName("Untitled " + type.name().toLowerCase() + " task");
        t.setPosition(m.getTasks().size());
        if (type == ProgramTaskType.LESSON) {
            ProgramTaskField intro = new ProgramTaskField();
            intro.setTask(t);
            intro.setFieldType(FieldType.INSTRUCTIONS);
            intro.setRequired(false);
            intro.setPosition(0);
            intro.setConfig(new LinkedHashMap<>(Map.of("text", "Describe what the founder needs to do.")));
            t.getFields().add(intro);
        } else if (type == ProgramTaskType.ASSESSMENT) {
            // A valid default (check-ins are the common case); the builder can
            // switch to BASELINE/DISTANCE, which uniqueness-validates on save.
            t.setMilestoneRole(MilestoneRole.CHECKIN);
        }
        m.getTasks().add(t);
        return ProgramMapper.toDto(tasks.save(t));
    }

    public TaskDto updateTask(UUID cohortId, UUID taskId, UpdateTaskRequest req) {
        cohortService.requireEditable(cohortId);
        ProgramTask t = requireTask(cohortId, taskId);
        // Additive contract: a request without taskType (pre-spine builder)
        // leaves the whole type/ref/milestone trio untouched.
        ProgramTaskType type = req.taskType() == null ? t.getTaskType() : req.taskType();
        UUID refId = req.taskType() == null ? t.getRefId() : req.refId();
        MilestoneRole role = req.taskType() == null ? t.getMilestoneRole() : req.milestoneRole();
        validateSpine(cohortId, t, type, refId, role, req.status(), req.fields().size());
        t.setTaskType(type);
        t.setRefId(refId);
        t.setMilestoneRole(role);
        t.setName(req.name());
        t.setDueDate(req.dueDate());
        t.setStatus(req.status());
        t.setAiDraft(req.aiDraft());
        reconcileFields(t, req.fields());
        return ProgramMapper.toDto(t);
    }

    /**
     * Typed-spine rules (spec §1/§5): the per-task structural rules from
     * {@link ProgramRules#taskTypeFieldErrors}, plus the cohort-level ones —
     * at most ONE BASELINE and ONE DISTANCE milestone per cohort, and a
     * BASELINE/DISTANCE task's pipeline must match the designated pair when
     * one is designated. (Task moves stay within the cohort by construction,
     * so create/update cover every mutation that could break these.)
     */
    private void validateSpine(UUID cohortId, ProgramTask t, ProgramTaskType type,
            UUID refId, MilestoneRole role,
            com.bvisionry.programflow.domain.ProgramTaskStatus status, int fieldCount) {
        Map<String, String> errors = new LinkedHashMap<>(
                ProgramRules.taskTypeFieldErrors(type, refId, role, status, fieldCount));
        // The reference must actually exist in its owning slice (review #7a);
        // a LIVE course task also requires the course to be published.
        if (errors.isEmpty() && refId != null
                && !spine.refExists(type, refId,
                        status == com.bvisionry.programflow.domain.ProgramTaskStatus.LIVE)) {
            errors.put("refId", "The referenced " + type.name().toLowerCase()
                    + (type == ProgramTaskType.COURSE
                            ? " was not found or is not published." : " was not found."));
        }
        // Once members have answered a milestone, its instrument is frozen —
        // re-pointing the ref would orphan their tagged submissions (review #7b).
        if (errors.isEmpty() && t.getTaskType() == ProgramTaskType.ASSESSMENT
                && t.getRefId() != null && !t.getRefId().equals(refId)
                && spine.hasTaggedSubmissions(t.getId())) {
            errors.put("refId", "Members have already answered this milestone — "
                    + "its assessment pipeline can no longer change.");
        }
        if (errors.isEmpty() && (role == MilestoneRole.BASELINE || role == MilestoneRole.DISTANCE)) {
            boolean taken = tasks.findByCohortAndMilestoneRole(cohortId, role).stream()
                    .anyMatch(other -> !other.getId().equals(t.getId()));
            if (taken) {
                errors.put("milestoneRole", "This cohort already has a "
                        + role.name().toLowerCase() + " milestone task.");
            }
            UUID designated = settings.findById(cohortId)
                    .map(s -> role == MilestoneRole.BASELINE
                            ? s.getBaselinePipelineId() : s.getDistancePipelineId())
                    .orElse(null);
            if (designated != null && refId != null && !designated.equals(refId)) {
                errors.put("refId", "The cohort's designated " + role.name().toLowerCase()
                        + " assessment is a different pipeline.");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    /** Deletes the task with its fields/submissions (orphan removal + DB cascades). */
    public void deleteTask(UUID cohortId, UUID taskId) {
        cohortService.requireEditable(cohortId);
        ProgramTask t = requireTask(cohortId, taskId);
        ProgramModule m = t.getModule();
        m.getTasks().remove(t);
        int position = 0;
        for (ProgramTask remaining : m.getTasks()) {
            remaining.setPosition(position++);
        }
    }

    /**
     * Board drag-and-drop: moves the task to {@code req.moduleId()} at
     * {@code req.position()} (same module = reorder) and compacts positions on
     * both sides. Only the owning side ({@code task.module} + positions) is
     * written — removing from the source collection would orphan-delete the task.
     */
    public TaskDto moveTask(UUID cohortId, UUID taskId, MoveTaskRequest req) {
        cohortService.requireEditable(cohortId);
        ProgramTask t = requireTask(cohortId, taskId);
        ProgramModule source = t.getModule();
        ProgramModule target = requireModule(cohortId, req.moduleId());

        if (!source.getId().equals(target.getId())) {
            int position = 0;
            for (ProgramTask remaining : source.getTasks()) {
                if (!remaining.getId().equals(taskId)) {
                    remaining.setPosition(position++);
                }
            }
        }

        List<ProgramTask> reordered = target.getTasks().stream()
                .filter(x -> !x.getId().equals(taskId))
                .collect(Collectors.toCollection(ArrayList::new));
        reordered.add(Math.min(req.position(), reordered.size()), t);
        t.setModule(target);
        int position = 0;
        for (ProgramTask task : reordered) {
            task.setPosition(position++);
        }
        return ProgramMapper.toDto(t);
    }

    /**
     * Replaces the task's field list with the submitted one, keeping the
     * managed entities for ids that survive so learner answers (keyed by field
     * id) stay attached across edits.
     */
    private void reconcileFields(ProgramTask t, List<FieldUpsert> upserts) {
        var keptIds = upserts.stream().map(FieldUpsert::id).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        t.getFields().removeIf(f -> !keptIds.contains(f.getId()));
        Map<UUID, ProgramTaskField> byId = t.getFields().stream()
                .collect(Collectors.toMap(ProgramTaskField::getId, Function.identity()));

        int position = 0;
        for (FieldUpsert fu : upserts) {
            ProgramTaskField f = fu.id() == null ? null : byId.get(fu.id());
            if (f == null) {
                f = new ProgramTaskField();
                f.setTask(t);
                t.getFields().add(f);
            }
            f.setFieldType(fu.type());
            f.setRequired(fu.type().answerable() && fu.required());
            f.setPosition(position++);
            f.setConfig(new LinkedHashMap<>(fu.config()));
        }
    }

    /** "Add to board": persists an AI-composed draft as a module of AI-draft tasks. */
    public ModuleDto addDraftModule(UUID cohortId, com.bvisionry.programflow.dto.ModuleDraft draft) {
        cohortService.requireEditable(cohortId);
        ProgramModule m = new ProgramModule();
        m.setCohortId(cohortId);
        m.setName(draft.name());
        m.setSummary(draft.summary());
        m.setPosition(modules.findByCohortIdOrderByPositionAsc(cohortId).size());

        int taskPosition = 0;
        for (var draftTask : draft.tasks()) {
            ProgramTask t = new ProgramTask();
            t.setModule(m);
            t.setName(draftTask.name());
            t.setDueDate(draftTask.dueDate());
            t.setAiDraft(true);
            t.setPosition(taskPosition++);
            int fieldPosition = 0;
            for (var draftField : draftTask.fields()) {
                ProgramTaskField f = new ProgramTaskField();
                f.setTask(t);
                f.setFieldType(draftField.type());
                f.setRequired(draftField.type().answerable() && draftField.required());
                f.setPosition(fieldPosition++);
                f.setConfig(new LinkedHashMap<>(draftField.config()));
                t.getFields().add(f);
            }
            m.getTasks().add(t);
        }
        m = modules.save(m);
        return ProgramMapper.toDto(m, reached(m, cohortFounders(cohortId)));
    }

    // ------------------------------------------------------------------ pulse

    @Transactional(readOnly = true)
    public PulseResponse getPulse(UUID cohortId) {
        List<CohortMemberRow> founders = cohortFounders(cohortId);
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<PulseColumn> columns = new ArrayList<>();
        List<UUID> taskIds = new ArrayList<>();
        List<ProgramModule> columnModules = new ArrayList<>();
        for (int mi = 0; mi < mods.size(); mi++) {
            List<ProgramTask> live = ProgramRules.liveTasks(mods.get(mi));
            for (int ti = 0; ti < live.size(); ti++) {
                ProgramTask task = live.get(ti);
                columns.add(new PulseColumn(task.getId(), mi + 1, ti + 1,
                        mods.get(mi).getName(), task.getName(), task.getDueDate()));
                taskIds.add(task.getId());
                columnModules.add(mods.get(mi));
            }
        }

        Map<UUID, Map<UUID, ProgramSubmission>> byUserThenTask = taskIds.isEmpty()
                ? Map.of()
                : submissions.findByTaskIdIn(taskIds).stream().collect(Collectors.groupingBy(
                        ProgramSubmission::getUserId,
                        Collectors.toMap(ProgramSubmission::getTaskId, Function.identity())));

        // Non-LESSON columns read their owning slice, batched for every founder.
        List<ProgramTask> typedTasks = mods.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() != ProgramTaskType.LESSON)
                .toList();
        Map<UUID, Map<UUID, JourneyTaskState>> typedByUser = myProgramService.typedStatesForPulse(
                founders.stream().map(CohortMemberRow::getId).toList(), typedTasks);
        Map<UUID, ProgramTaskType> taskTypes = mods.stream()
                .flatMap(m -> m.getTasks().stream())
                .collect(Collectors.toMap(ProgramTask::getId, ProgramTask::getTaskType));

        List<PulseRow> rows = founders.stream().map(member -> {
            Map<UUID, ProgramSubmission> mine = byUserThenTask.getOrDefault(member.getId(), Map.of());
            Map<UUID, JourneyTaskState> myTyped = typedByUser.getOrDefault(member.getId(), Map.of());
            List<CellState> cells = new ArrayList<>(taskIds.size());
            int assigned = 0;
            long done = 0;
            for (int i = 0; i < taskIds.size(); i++) {
                // A member outside a module's audience was never given its tasks;
                // don't score them against work they can't see (mirrors the
                // learner journey's ProgramRules.includes visibility).
                if (!ProgramRules.includes(columnModules.get(i), member.getId())) {
                    cells.add(CellState.NOT_ASSIGNED);
                    continue;
                }
                UUID taskId = taskIds.get(i);
                ProgramTaskType type = taskTypes.get(taskId);
                CellState state;
                if (type == ProgramTaskType.LESSON) {
                    ProgramSubmission s = mine.get(taskId);
                    state = s == null
                            ? CellState.NOT_STARTED
                            : s.getStatus() == SubmissionStatus.SUBMITTED ? CellState.SUBMITTED : CellState.IN_DRAFT;
                } else {
                    state = cellOf(myTyped.get(taskId));
                }
                // A task that does not GATE renders a cell but counts in neither
                // side of the completion percentage (ProgramRules.gates).
                if (type.completableInApp()) {
                    assigned++;
                    if (state == CellState.SUBMITTED) {
                        done++;
                    }
                }
                cells.add(state);
            }
            int pct = assigned == 0 ? 0 : Math.round(done * 100f / assigned);
            return new PulseRow(member.getId(), member.getName(), member.getOrgName(), cells, pct);
        }).toList();

        int dueSoonDays = ProgramMapper.toDto(settings.findById(cohortId).orElse(null)).dueSoonDays();
        return new PulseResponse(columns, rows, dueSoonDays);
    }

    // ----------------------------------------------------------------- matrix

    /** Platform key for the needs-attention "pillar under threshold" rule (§11). */
    static final String KEY_PILLAR_THRESHOLD = "attention.pillar_threshold";
    static final int DEFAULT_PILLAR_THRESHOLD = 40;
    private static final int IDLE_DAYS = 7;

    /**
     * The cohort board's Founders tab (spec §2.3): the progress matrix over the
     * enrolled founders. Works for a cohort in any lifecycle state — admins may
     * inspect drafts and archives.
     */
    @Transactional(readOnly = true)
    public CohortMatrixResponse getMatrix(UUID cohortId) {
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<CohortMemberRow> founders = cohortFounders(cohortId);
        List<UUID> founderIds = founders.stream().map(CohortMemberRow::getId).toList();

        List<ModuleColumn> moduleColumns = mods.stream()
                .map(m -> new ModuleColumn(m.getId(), m.getName(), m.getPillarLabel(), m.getPosition()))
                .toList();

        // Milestone columns: LIVE ASSESSMENT tasks carrying a milestone role, board order.
        List<ProgramTask> milestoneTasks = mods.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() == ProgramTaskType.ASSESSMENT
                        && t.getMilestoneRole() != null)
                .toList();
        List<MilestoneColumn> milestoneColumns = milestoneTasks.stream()
                .map(t -> new MilestoneColumn(t.getId(), t.getName(), t.getMilestoneRole(),
                        t.getDueDate()))
                .toList();

        // Per-founder per-task done-state, via the same machinery as the pulse
        // and the member journey (single source of done semantics).
        List<ProgramTask> typedTasks = mods.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() != ProgramTaskType.LESSON)
                .toList();
        Map<UUID, Map<UUID, MyProgramService.TypedState>> typedByUser =
                myProgramService.typedStates(founderIds, typedTasks);
        List<UUID> lessonTaskIds = mods.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() == ProgramTaskType.LESSON)
                .map(ProgramTask::getId)
                .toList();
        Map<UUID, Map<UUID, ProgramSubmission>> lessonByUser = lessonTaskIds.isEmpty()
                ? Map.of()
                : submissions.findByTaskIdIn(lessonTaskIds).stream().collect(Collectors.groupingBy(
                        ProgramSubmission::getUserId,
                        Collectors.toMap(ProgramSubmission::getTaskId, Function.identity())));

        Map<UUID, CohortBoardReadRepository.TriageRow> triage = boardReads
                .triage(founderIds).stream()
                .collect(Collectors.toMap(CohortBoardReadRepository.TriageRow::userId,
                        Function.identity()));
        int pillarThreshold = boardReads.platformInt(KEY_PILLAR_THRESHOLD, DEFAULT_PILLAR_THRESHOLD);

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.OffsetDateTime idleCutoff = java.time.OffsetDateTime.now().minusDays(IDLE_DAYS);
        // Spec §3/§13: course visibility is per MEMBER ORG on a cross-org
        // roster — one batched read per distinct org, not per founder.
        Map<UUID, Set<UUID>> blockedByOrg = founders.stream()
                .map(CohortMemberRow::getOrgId).distinct()
                .collect(Collectors.toMap(Function.identity(),
                        org -> myProgramService.blockedCourseIds(org, mods)));
        Set<UUID> overdueCourseMembers = spine.membersWithOverdueCourses(cohortId);

        List<FounderRow> rows = founders.stream().map(member -> {
            Set<UUID> blockedCourses = blockedByOrg.getOrDefault(member.getOrgId(), Set.of());
            Map<UUID, MyProgramService.TypedState> myTyped =
                    typedByUser.getOrDefault(member.getId(), Map.of());
            Map<UUID, ProgramSubmission> myLessons =
                    lessonByUser.getOrDefault(member.getId(), Map.of());

            boolean anyOverdue = overdueCourseMembers.contains(member.getId());
            boolean courseUnavailable = false;
            int maxDoneModulePosition = -1;

            List<ModuleCell> moduleCells = new ArrayList<>(mods.size());
            for (ProgramModule m : mods) {
                if (!ProgramRules.includes(m, member.getId())) {
                    moduleCells.add(new ModuleCell(false, 0, 0));
                    continue;
                }
                int done = 0;
                int total = 0;
                for (ProgramTask t : ProgramRules.liveTasks(m)) {
                    if (!ProgramRules.gates(t, blockedCourses)) {
                        // A course the org can no longer open holds nothing and
                        // counts nowhere — but it is worth an admin's attention.
                        courseUnavailable = courseUnavailable
                                || t.getTaskType() == ProgramTaskType.COURSE;
                        continue;
                    }
                    boolean taskDone = isDone(t, myLessons, myTyped);
                    // A milestone assessment has its OWN column on this matrix
                    // (baseline / check-in / distance) and the member's journey
                    // renders it outside the module list too — counting it in
                    // the module cell as well double-counts and makes the two
                    // surfaces quote different fractions for the same module.
                    if (t.getMilestoneRole() == null) {
                        total++;
                        if (taskDone) {
                            done++;
                        }
                    }
                    if (taskDone) {
                        maxDoneModulePosition = Math.max(maxDoneModulePosition, m.getPosition());
                    }
                    if (!taskDone && t.getDueDate() != null && t.getDueDate().isBefore(today)) {
                        anyOverdue = true;
                    }
                }
                moduleCells.add(new ModuleCell(true, done, total));
            }

            // "Walked past a check-in": ANY non-DISTANCE milestone (baseline or
            // mid-program check-in) left undone while the founder has already
            // produced work in a LATER module. DISTANCE is exempt — it is the
            // end-of-cohort measurement, everything is "later work" before it.
            boolean skippedCheckin = false;
            List<MilestoneCell> milestoneCells = new ArrayList<>(milestoneTasks.size());
            for (ProgramTask t : milestoneTasks) {
                boolean assigned = ProgramRules.includes(t.getModule(), member.getId());
                MyProgramService.TypedState ts = myTyped.getOrDefault(t.getId(),
                        MyProgramService.TypedState.NOT_STARTED);
                milestoneCells.add(new MilestoneCell(assigned, ts.state(), ts.score(),
                        ts.submittedAt(), ts.completedAt()));
                if (t.getMilestoneRole() != MilestoneRole.DISTANCE && assigned
                        && !ProgramRules.done(ts.state())
                        && maxDoneModulePosition > t.getModule().getPosition()) {
                    skippedCheckin = true;
                }
            }

            CohortBoardReadRepository.TriageRow tri = triage.get(member.getId());
            List<AttentionFlag> flags = new ArrayList<>();
            // Null last-activity is "no footprint yet", not "idle since the
            // dawn of time": a founder who joined minutes ago must not be
            // flagged IDLE (null-not-zero — the row's Last seen already reads
            // "—", which is the honest signal).
            if (tri != null && tri.lastActivityAt() != null
                    && tri.lastActivityAt().isBefore(idleCutoff)) {
                flags.add(AttentionFlag.IDLE);
            }
            if (anyOverdue) {
                flags.add(AttentionFlag.OVERDUE_TASKS);
            }
            if (skippedCheckin) {
                flags.add(AttentionFlag.CHECKIN_UNSTARTED);
            }
            if (courseUnavailable) {
                flags.add(AttentionFlag.COURSE_UNAVAILABLE);
            }
            if (tri != null && tri.minPillarScore() != null
                    && tri.minPillarScore().intValue() < pillarThreshold) {
                flags.add(AttentionFlag.PILLAR_BELOW_THRESHOLD);
            }

            java.math.BigDecimal friLatest = tri == null ? null : tri.friLatest();
            java.math.BigDecimal friDelta = tri == null || tri.evaluatedCount() < 2 ? null
                    : tri.friLatest().subtract(tri.friEarliest());
            return new FounderRow(member.getId(), member.getName(),
                    member.getOrgId(), member.getOrgName(),
                    moduleCells, milestoneCells,
                    friLatest, friDelta, tri == null ? 0 : tri.awaitingReview(),
                    tri == null ? null : tri.lastActivityAt(), flags);
        }).toList();

        return new CohortMatrixResponse(moduleColumns, milestoneColumns, rows, pillarThreshold);
    }

    /** Done per the shared spine rule: LESSON = SUBMITTED submission, typed = ProgramRules.done. */
    private static boolean isDone(ProgramTask t, Map<UUID, ProgramSubmission> myLessons,
            Map<UUID, MyProgramService.TypedState> myTyped) {
        if (t.getTaskType() == ProgramTaskType.LESSON) {
            ProgramSubmission sub = myLessons.get(t.getId());
            return sub != null && sub.getStatus() == SubmissionStatus.SUBMITTED;
        }
        MyProgramService.TypedState ts = myTyped.get(t.getId());
        return ts != null && ProgramRules.done(ts.state());
    }

    // ---------------------------------------------------------------- helpers

    /** Typed-task state → pulse cell: done-states read as SUBMITTED, activity as IN_DRAFT. */
    private static CellState cellOf(JourneyTaskState state) {
        if (state == null || state == JourneyTaskState.NOT_STARTED) {
            return CellState.NOT_STARTED;
        }
        return ProgramRules.done(state) ? CellState.SUBMITTED : CellState.IN_DRAFT;
    }

    /**
     * The cohort's roster: active enrolled members with their orgs (spec §13
     * — a roster may span orgs), the single source every cohort-board count
     * reads (board stats, module "reached", pulse, matrix). {@code require}
     * still owns the existence check.
     */
    private List<CohortMemberRow> cohortFounders(UUID cohortId) {
        cohortService.require(cohortId);
        return cohorts.findRoster(cohortId);
    }

    private int reached(ProgramModule m, List<CohortMemberRow> members) {
        return (int) members.stream()
                .filter(member -> ProgramRules.includes(m, member.getId()))
                .count();
    }

    /** {@link #requireModule} behind the cohort's ARCHIVED read-only gate. */
    private ProgramModule requireEditableModule(UUID cohortId, UUID moduleId) {
        cohortService.requireEditable(cohortId);
        return requireModule(cohortId, moduleId);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private ProgramModule requireModule(UUID cohortId, UUID moduleId) {
        return modules.findById(moduleId)
                .filter(m -> m.getCohortId().equals(cohortId))
                .orElseThrow(() -> new ResourceNotFoundException("Module", moduleId.toString()));
    }

    private ProgramTask requireTask(UUID cohortId, UUID taskId) {
        return tasks.findWithModule(taskId)
                .filter(t -> t.getModule().getCohortId().equals(cohortId))
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
    }
}
