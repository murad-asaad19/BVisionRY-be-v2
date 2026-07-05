package com.bvisionry.programflow.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.FieldType;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramSettings;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.dto.AudienceDto;
import com.bvisionry.programflow.dto.BoardResponse;
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
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/** Admin (program director) operations: board, modules, tasks, pulse, settings. */
@Service
@RequiredArgsConstructor
@Transactional
public class ProgramAdminService {

    private final ProgramModuleRepository modules;
    private final ProgramTaskRepository tasks;
    private final ProgramSubmissionRepository submissions;
    private final ProgramSettingsRepository settings;
    private final TeamRepository teams;
    private final CohortService cohortService;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------ board

    @Transactional(readOnly = true)
    public BoardResponse getBoard(UUID orgId, UUID cohortId) {
        cohortService.require(orgId, cohortId);
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<OrgMemberRow> members = teams.findOrgMembers(orgId);
        List<ModuleDto> moduleDtos = mods.stream()
                .map(m -> ProgramMapper.toDto(m, reached(m, members)))
                .toList();
        int taskCount = mods.stream().mapToInt(m -> m.getTasks().size()).sum();
        return new BoardResponse(
                ProgramMapper.toDto(settings.findById(cohortId).orElse(null)),
                moduleDtos,
                new BoardResponse.BoardStats(mods.size(), taskCount, members.size()));
    }

    public ProgramSettingsDto updateSettings(UUID orgId, UUID cohortId, ProgramSettingsDto req) {
        cohortService.require(orgId, cohortId);
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
        return ProgramMapper.toDto(settings.save(s));
    }

    // ---------------------------------------------------------------- modules

    public ModuleDto createModule(UUID orgId, UUID cohortId, CreateModuleRequest req) {
        cohortService.require(orgId, cohortId);
        ProgramModule m = new ProgramModule();
        m.setOrgId(orgId);
        m.setCohortId(cohortId);
        m.setName(req.name());
        m.setSummary(req.summary());
        m.setPosition(modules.findByCohortIdOrderByPositionAsc(cohortId).size());
        return ProgramMapper.toDto(modules.save(m), reached(m, teams.findOrgMembers(orgId)));
    }

    public ModuleDto updateModule(UUID orgId, UUID cohortId, UUID moduleId, UpdateModuleRequest req) {
        ProgramModule m = requireModule(orgId, cohortId, moduleId);
        m.setName(req.name());
        m.setSummary(req.summary());
        m.setLockMode(req.lockMode());
        m.setUnlockAt(req.unlockAt());
        return ProgramMapper.toDto(m, reached(m, teams.findOrgMembers(orgId)));
    }

    public AudienceDto updateAudience(UUID orgId, UUID cohortId, UUID moduleId, UpdateAudienceRequest req) {
        ProgramModule m = requireModule(orgId, cohortId, moduleId);
        List<OrgMemberRow> members = teams.findOrgMembers(orgId);

        if (req.mode() == AudienceMode.TEAMS) {
            var orgTeamIds = teams.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                    .map(t -> t.getId()).collect(Collectors.toSet());
            if (!orgTeamIds.containsAll(req.teamIds())) {
                throw new BadRequestException("One or more teams do not belong to this organization");
            }
        }
        if (req.mode() == AudienceMode.MEMBERS) {
            var orgMemberIds = members.stream().map(OrgMemberRow::getId).collect(Collectors.toSet());
            if (!orgMemberIds.containsAll(req.memberIds())) {
                throw new BadRequestException("One or more members do not belong to this organization");
            }
        }

        var includedBefore = members.stream()
                .filter(member -> ProgramRules.includes(m, member.getId(), member.getTeamId()))
                .map(OrgMemberRow::getId)
                .collect(Collectors.toSet());

        m.setAssignMode(req.mode());
        m.setTeamIds(new LinkedHashSet<>(req.teamIds()));
        m.setMemberIds(new LinkedHashSet<>(req.memberIds()));

        // "New module assigned" for learners the audience newly reaches — only
        // ones enrolled in this cohort (audience teams/members are org-scoped).
        var cohort = cohortService.require(orgId, cohortId);
        List<UUID> newlyAssigned = members.stream()
                .filter(member -> cohort.getMemberIds().contains(member.getId()))
                .filter(member -> !includedBefore.contains(member.getId()))
                .filter(member -> ProgramRules.includes(m, member.getId(), member.getTeamId()))
                .map(OrgMemberRow::getId)
                .toList();
        if (!newlyAssigned.isEmpty()) {
            events.publishEvent(new ProgramFlowEvents.ModuleAssigned(
                    orgId, m.getName(), cohort.getName(), newlyAssigned));
        }

        return new AudienceDto(m.getAssignMode(), List.copyOf(m.getTeamIds()),
                List.copyOf(m.getMemberIds()), reached(m, members));
    }

    /** Deletes the module with its tasks/fields/submissions (DB cascades). */
    public void deleteModule(UUID orgId, UUID cohortId, UUID moduleId) {
        ProgramModule m = requireModule(orgId, cohortId, moduleId);
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

    public TaskDto createTask(UUID orgId, UUID cohortId, UUID moduleId) {
        ProgramModule m = requireModule(orgId, cohortId, moduleId);
        ProgramTask t = new ProgramTask();
        t.setModule(m);
        t.setName("Untitled task");
        t.setPosition(m.getTasks().size());
        ProgramTaskField intro = new ProgramTaskField();
        intro.setTask(t);
        intro.setFieldType(FieldType.INSTRUCTIONS);
        intro.setRequired(false);
        intro.setPosition(0);
        intro.setConfig(new LinkedHashMap<>(Map.of("text", "Describe what the founder needs to do.")));
        t.getFields().add(intro);
        m.getTasks().add(t);
        return ProgramMapper.toDto(tasks.save(t));
    }

    public TaskDto updateTask(UUID orgId, UUID cohortId, UUID taskId, UpdateTaskRequest req) {
        ProgramTask t = requireTask(orgId, cohortId, taskId);
        t.setName(req.name());
        t.setDueDate(req.dueDate());
        t.setStatus(req.status());
        t.setAiDraft(req.aiDraft());
        reconcileFields(t, req.fields());
        return ProgramMapper.toDto(t);
    }

    /** Deletes the task with its fields/submissions (orphan removal + DB cascades). */
    public void deleteTask(UUID orgId, UUID cohortId, UUID taskId) {
        ProgramTask t = requireTask(orgId, cohortId, taskId);
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
    public TaskDto moveTask(UUID orgId, UUID cohortId, UUID taskId, MoveTaskRequest req) {
        ProgramTask t = requireTask(orgId, cohortId, taskId);
        ProgramModule source = t.getModule();
        ProgramModule target = requireModule(orgId, cohortId, req.moduleId());

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
    public ModuleDto addDraftModule(UUID orgId, UUID cohortId, com.bvisionry.programflow.dto.ModuleDraft draft) {
        cohortService.require(orgId, cohortId);
        ProgramModule m = new ProgramModule();
        m.setOrgId(orgId);
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
        return ProgramMapper.toDto(m, reached(m, teams.findOrgMembers(orgId)));
    }

    // ------------------------------------------------------------------ pulse

    @Transactional(readOnly = true)
    public PulseResponse getPulse(UUID orgId, UUID cohortId) {
        cohortService.require(orgId, cohortId);
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

        Map<UUID, String> teamNames = teams.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));

        List<PulseRow> rows = teams.findOrgMembers(orgId).stream().map(member -> {
            Map<UUID, ProgramSubmission> mine = byUserThenTask.getOrDefault(member.getId(), Map.of());
            List<CellState> cells = new ArrayList<>(taskIds.size());
            int assigned = 0;
            long done = 0;
            for (int i = 0; i < taskIds.size(); i++) {
                // A member outside a module's audience was never given its tasks;
                // don't score them against work they can't see (mirrors the
                // learner journey's ProgramRules.includes visibility).
                if (!ProgramRules.includes(columnModules.get(i), member.getId(), member.getTeamId())) {
                    cells.add(CellState.NOT_ASSIGNED);
                    continue;
                }
                assigned++;
                ProgramSubmission s = mine.get(taskIds.get(i));
                CellState state = s == null
                        ? CellState.NOT_STARTED
                        : s.getStatus() == SubmissionStatus.SUBMITTED ? CellState.SUBMITTED : CellState.IN_DRAFT;
                if (state == CellState.SUBMITTED) {
                    done++;
                }
                cells.add(state);
            }
            int pct = assigned == 0 ? 0 : Math.round(done * 100f / assigned);
            return new PulseRow(member.getId(), member.getName(),
                    member.getTeamId() == null ? null : teamNames.get(member.getTeamId()), cells, pct);
        }).toList();

        int dueSoonDays = ProgramMapper.toDto(settings.findById(cohortId).orElse(null)).dueSoonDays();
        return new PulseResponse(columns, rows, dueSoonDays);
    }

    // ---------------------------------------------------------------- helpers

    private int reached(ProgramModule m, List<OrgMemberRow> members) {
        return (int) members.stream()
                .filter(member -> ProgramRules.includes(m, member.getId(), member.getTeamId()))
                .count();
    }

    private ProgramModule requireModule(UUID orgId, UUID cohortId, UUID moduleId) {
        return modules.findById(moduleId)
                .filter(m -> m.getCohortId().equals(cohortId) && m.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Module", moduleId.toString()));
    }

    private ProgramTask requireTask(UUID orgId, UUID cohortId, UUID taskId) {
        return tasks.findWithModule(taskId)
                .filter(t -> t.getModule().getCohortId().equals(cohortId)
                        && t.getModule().getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
    }
}
