package com.bvisionry.workshops.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.workshops.domain.Workshop;
import com.bvisionry.workshops.domain.WorkshopExercise;
import com.bvisionry.workshops.domain.WorkshopExerciseRun;
import com.bvisionry.workshops.domain.WorkshopExerciseTask;
import com.bvisionry.workshops.domain.WorkshopStatus;
import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskType;
import com.bvisionry.workshops.domain.WorkshopTeam;
import com.bvisionry.workshops.dto.AssignmentsResponse;
import com.bvisionry.workshops.dto.BuilderResponse;
import com.bvisionry.workshops.dto.CreateExerciseRequest;
import com.bvisionry.workshops.dto.CreateTaskRequest;
import com.bvisionry.workshops.dto.CreateWorkshopRequest;
import com.bvisionry.workshops.dto.ReorderRequest;
import com.bvisionry.workshops.dto.UpdateBuilderRequest;
import com.bvisionry.workshops.dto.UpdatePipelineRequest;
import com.bvisionry.workshops.dto.UpdateTaskRequest;
import com.bvisionry.workshops.dto.UpdateWorkshopRequest;
import com.bvisionry.workshops.dto.WorkshopAnalyticsResponse;
import com.bvisionry.workshops.dto.WorkshopDto;
import com.bvisionry.workshops.dto.WorkshopLiveResponse;
import com.bvisionry.workshops.repository.WorkshopExerciseRepository;
import com.bvisionry.workshops.repository.WorkshopExerciseRunRepository;
import com.bvisionry.workshops.repository.WorkshopExerciseTaskRepository;
import com.bvisionry.workshops.repository.WorkshopRepository;
import com.bvisionry.workshops.repository.WorkshopTaskSubmissionRepository;
import com.bvisionry.workshops.repository.WorkshopTeamRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin management of workshops, their exercise pipelines and analytics.
 *
 * <p>Every mutation of an exercise's pipeline (task create/update/delete/
 * reorder, exercise delete) resets that exercise's in-progress runs and
 * submissions — the config they were produced under no longer exists, exactly
 * like the prototype's "any edit resets the run" rule.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkshopAdminService {

    private final WorkshopRepository workshops;
    private final WorkshopTeamRepository teams;
    private final WorkshopExerciseRepository exercises;
    private final WorkshopExerciseTaskRepository tasks;
    private final WorkshopExerciseRunRepository runs;
    private final WorkshopTaskSubmissionRepository submissions;

    // ------------------------------------------------------------ workshops

    @Transactional(readOnly = true)
    public List<WorkshopDto> list(UUID orgId) {
        return workshops.findByOrgIdOrderByPositionAscCreatedAtAsc(orgId).stream()
                .map(w -> WorkshopDto.from(w,
                        exercises.countByWorkshopId(w.getId()),
                        teams.countWorkshopMembers(w.getId())))
                .toList();
    }

    public WorkshopDto create(UUID orgId, CreateWorkshopRequest req) {
        if (workshops.existsByOrgIdAndNameIgnoreCase(orgId, req.name().trim())) {
            throw new DuplicateResourceException("A workshop with this name already exists");
        }
        Workshop w = new Workshop();
        w.setOrgId(orgId);
        w.setName(req.name().trim());
        w.setPosition(workshops.nextPosition(orgId));
        Workshop saved = workshops.saveAndFlush(w);
        return WorkshopDto.from(saved, 0, 0);
    }

    public WorkshopDto update(UUID orgId, UUID workshopId, UpdateWorkshopRequest req) {
        Workshop w = requireWorkshop(orgId, workshopId);
        validateStatusChange(w, req.status());
        w.setName(req.name().trim());
        w.setStatus(req.status());
        w.setPostCompletionSurveyId(req.postCompletionSurveyId());
        w.setPreWorkshopSurveyId(req.preWorkshopSurveyId());
        Workshop saved = workshops.save(w);
        return WorkshopDto.from(saved,
                exercises.countByWorkshopId(workshopId),
                teams.countWorkshopMembers(workshopId));
    }

    public void delete(UUID orgId, UUID workshopId) {
        workshops.delete(requireWorkshop(orgId, workshopId));
    }

    /** Persist the admin's live-board road style pick. */
    public void setBoardStyle(UUID orgId, UUID workshopId, String style) {
        Workshop w = requireWorkshop(orgId, workshopId);
        w.setBoardStyle(style);
        workshops.save(w);
    }

    /** Clears every run, submission, response and open help ping of the workshop (admin reset). */
    public void reset(UUID orgId, UUID workshopId) {
        requireWorkshop(orgId, workshopId);
        submissions.deleteByWorkshopId(workshopId);
        runs.deleteByWorkshopId(workshopId);
        workshops.deleteIntroResponsesByWorkshopId(workshopId);
        List<WorkshopTeam> all = teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId);
        all.forEach(t -> t.setHelpRequestedAt(null));
        teams.saveAll(all);
    }

    // ------------------------------------------------------------ builder

    @Transactional(readOnly = true)
    public BuilderResponse builder(UUID orgId, UUID workshopId) {
        requireWorkshop(orgId, workshopId);
        List<BuilderResponse.ExerciseDto> out = new ArrayList<>();
        for (WorkshopExercise e : exercises.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId)) {
            List<BuilderResponse.TaskDto> taskDtos =
                    tasks.findByExerciseIdOrderByPositionAscCreatedAtAsc(e.getId()).stream()
                            .map(BuilderResponse.TaskDto::from)
                            .toList();
            out.add(BuilderResponse.ExerciseDto.from(e, taskDtos));
        }
        return new BuilderResponse(out);
    }

    public BuilderResponse.ExerciseDto createExercise(UUID orgId, UUID workshopId, CreateExerciseRequest req) {
        requireDraftWorkshop(orgId, workshopId);
        WorkshopExercise e = new WorkshopExercise();
        e.setWorkshopId(workshopId);
        e.setTitle(req.title().trim());
        e.setPosition(exercises.nextPosition(workshopId));
        return BuilderResponse.ExerciseDto.from(exercises.saveAndFlush(e), List.of());
    }

    public void renameExercise(UUID orgId, UUID workshopId, UUID exerciseId, CreateExerciseRequest req) {
        WorkshopExercise e = requireExercise(orgId, workshopId, exerciseId);
        e.setTitle(req.title().trim());
        exercises.save(e);
    }

    public void deleteExercise(UUID orgId, UUID workshopId, UUID exerciseId) {
        exercises.delete(requireExercise(orgId, workshopId, exerciseId));
    }

    public void reorderExercises(UUID orgId, UUID workshopId, ReorderRequest req) {
        requireDraftWorkshop(orgId, workshopId);
        List<WorkshopExercise> all = exercises.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId);
        applyOrder(req.orderedIds(), all, WorkshopExercise::getId,
                (e, pos) -> e.setPosition(pos));
        exercises.saveAll(all);
    }

    // ------------------------------------------------------------ tasks

    public BuilderResponse.TaskDto createTask(UUID orgId, UUID workshopId, UUID exerciseId, CreateTaskRequest req) {
        requireExercise(orgId, workshopId, exerciseId);
        WorkshopExerciseTask t = new WorkshopExerciseTask();
        t.setExerciseId(exerciseId);
        t.setTaskType(req.type());
        t.setAssignee(req.assignee());
        t.setTitle(req.title().trim());
        t.setPosition(tasks.nextPosition(exerciseId));
        if (req.config() != null) {
            t.setConfig(req.config());
        }
        WorkshopExerciseTask saved = tasks.saveAndFlush(t);
        resetExerciseRuns(exerciseId);
        return BuilderResponse.TaskDto.from(saved);
    }

    public BuilderResponse.TaskDto updateTask(UUID orgId, UUID workshopId, UUID exerciseId,
                                              UUID taskId, UpdateTaskRequest req) {
        requireExercise(orgId, workshopId, exerciseId);
        WorkshopExerciseTask t = requireTask(exerciseId, taskId);
        t.setAssignee(req.assignee());
        t.setTitle(req.title().trim());
        t.setConfig(req.config() != null ? req.config() : Map.of());
        WorkshopExerciseTask saved = tasks.save(t);
        resetExerciseRuns(exerciseId);
        return BuilderResponse.TaskDto.from(saved);
    }

    public void deleteTask(UUID orgId, UUID workshopId, UUID exerciseId, UUID taskId) {
        requireExercise(orgId, workshopId, exerciseId);
        tasks.delete(requireTask(exerciseId, taskId));
        resetExerciseRuns(exerciseId);
    }

    /**
     * Replace the whole workshop builder in one transaction (the builder's
     * global "Save changes"): upserts exercises + their task pipelines in the
     * given order, deletes exercises/tasks missing from the draft, and resets
     * runs only for exercises whose pipeline actually changed. Returns the full
     * builder so the client can reseed its draft with the new server ids.
     */
    public BuilderResponse updateBuilder(UUID orgId, UUID workshopId, UpdateBuilderRequest req) {
        requireDraftWorkshop(orgId, workshopId);
        Map<UUID, WorkshopExercise> exercisesById = new LinkedHashMap<>();
        for (WorkshopExercise e : exercises.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId)) {
            exercisesById.put(e.getId(), e);
        }

        List<BuilderResponse.ExerciseDto> out = new ArrayList<>();
        Set<UUID> keepExercises = new HashSet<>();
        int pos = 0;
        for (UpdateBuilderRequest.ExerciseSpec spec : req.exercises()) {
            WorkshopExercise e;
            if (spec.id() != null) {
                e = exercisesById.get(spec.id());
                if (e == null) {
                    throw new BadRequestException("Unknown exercise: " + spec.id());
                }
                keepExercises.add(spec.id());
            } else {
                e = new WorkshopExercise();
                e.setWorkshopId(workshopId);
            }
            e.setTitle(spec.title().trim());
            e.setPosition(pos++);
            WorkshopExercise savedExercise = exercises.saveAndFlush(e);
            List<BuilderResponse.TaskDto> taskDtos =
                    replaceTasks(savedExercise.getId(), spec.tasks());
            out.add(BuilderResponse.ExerciseDto.from(savedExercise, taskDtos));
        }
        exercisesById.forEach((id, e) -> {
            if (!keepExercises.contains(id)) {
                exercises.delete(e); // cascades tasks, runs, submissions (DB FKs)
            }
        });
        return new BuilderResponse(out);
    }

    /**
     * Upsert an exercise's task pipeline in draft order, delete tasks missing
     * from the list, and reset the exercise's runs only if the pipeline
     * changed. New exercises (no prior tasks) never carry runs, so the guard
     * is a no-op for them.
     */
    private List<BuilderResponse.TaskDto> replaceTasks(
            UUID exerciseId, List<UpdatePipelineRequest.TaskSpec> specs) {
        List<WorkshopExerciseTask> existing =
                tasks.findByExerciseIdOrderByPositionAscCreatedAtAsc(exerciseId);
        boolean changed = pipelineChanged(existing, specs);

        Map<UUID, WorkshopExerciseTask> byId = new LinkedHashMap<>();
        existing.forEach(t -> byId.put(t.getId(), t));
        List<WorkshopExerciseTask> ordered = new ArrayList<>();
        Set<UUID> keep = new HashSet<>();
        int pos = 0;
        for (UpdatePipelineRequest.TaskSpec spec : specs) {
            WorkshopExerciseTask t;
            if (spec.id() != null) {
                t = byId.get(spec.id());
                if (t == null) {
                    throw new BadRequestException("Unknown task: " + spec.id());
                }
                keep.add(spec.id());
            } else {
                t = new WorkshopExerciseTask();
                t.setExerciseId(exerciseId);
                t.setTaskType(spec.type());
            }
            t.setAssignee(spec.assignee());
            t.setTitle(spec.title().trim());
            t.setConfig(spec.config() != null ? spec.config() : Map.of());
            t.setPosition(pos++);
            ordered.add(t);
        }
        byId.forEach((id, t) -> {
            if (!keep.contains(id)) {
                tasks.delete(t);
            }
        });
        List<WorkshopExerciseTask> saved = tasks.saveAllAndFlush(ordered);
        if (changed) {
            resetExerciseRuns(exerciseId);
        }
        return saved.stream().map(BuilderResponse.TaskDto::from).toList();
    }

    /** True when the draft pipeline differs from the stored one (order, fields or config). */
    private boolean pipelineChanged(List<WorkshopExerciseTask> old,
                                    List<UpdatePipelineRequest.TaskSpec> specs) {
        if (old.size() != specs.size()) {
            return true;
        }
        for (int i = 0; i < old.size(); i++) {
            WorkshopExerciseTask o = old.get(i);
            UpdatePipelineRequest.TaskSpec s = specs.get(i);
            Map<String, Object> cfg = s.config() != null ? s.config() : Map.of();
            if (s.id() == null || !o.getId().equals(s.id())
                    || o.getTaskType() != s.type()
                    || o.getAssignee() != s.assignee()
                    || !o.getTitle().equals(s.title().trim())
                    || !o.getConfig().equals(cfg)) {
                return true;
            }
        }
        return false;
    }

    public void reorderTasks(UUID orgId, UUID workshopId, UUID exerciseId, ReorderRequest req) {
        requireExercise(orgId, workshopId, exerciseId);
        List<WorkshopExerciseTask> all = tasks.findByExerciseIdOrderByPositionAscCreatedAtAsc(exerciseId);
        applyOrder(req.orderedIds(), all, WorkshopExerciseTask::getId,
                (t, pos) -> t.setPosition(pos));
        tasks.saveAll(all);
        resetExerciseRuns(exerciseId);
    }

    // ------------------------------------------------------------ assignments

    /** Each team's pinned card hands ({@code taskId → [cardId…]}), merged across exercises. */
    @Transactional(readOnly = true)
    public AssignmentsResponse assignments(UUID orgId, UUID workshopId) {
        requireWorkshop(orgId, workshopId);
        Map<UUID, Map<String, List<String>>> byTeam = new LinkedHashMap<>();
        for (WorkshopExerciseRun r : runs.findByWorkshopId(workshopId)) {
            Map<String, List<String>> deals =
                    byTeam.computeIfAbsent(r.getTeamId(), k -> new LinkedHashMap<>());
            r.getDeals().forEach((taskId, ids) -> deals.put(taskId, CardDealing.strList(ids)));
        }
        return new AssignmentsResponse(
                teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId).stream()
                        .map(t -> new AssignmentsResponse.TeamAssignments(
                                t.getId(), t.getName(), byTeam.getOrDefault(t.getId(), Map.of())))
                        .toList());
    }

    /**
     * Deal a SORT task's hand to every team that doesn't have one yet (random,
     * side-balanced — same dealer the runner uses lazily on task start).
     * Already-dealt teams keep their hand; change "cards per team" (which
     * resets the exercise's runs) to re-roll.
     */
    public AssignmentsResponse dealCards(UUID orgId, UUID workshopId, UUID taskId) {
        requireWorkshop(orgId, workshopId);
        WorkshopExerciseTask task = tasks.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        WorkshopExercise exercise = exercises.findById(task.getExerciseId())
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", task.getExerciseId().toString()));
        if (!exercise.getWorkshopId().equals(workshopId)) {
            throw new ResourceNotFoundException("Task", taskId.toString());
        }
        if (task.getTaskType() != WorkshopTaskType.SORT) {
            throw new BadRequestException("Only card-sort tasks deal hands");
        }
        Map<UUID, WorkshopExerciseRun> runByTeam = new LinkedHashMap<>();
        for (WorkshopExerciseRun r : runs.findByWorkshopId(workshopId)) {
            if (r.getExerciseId().equals(task.getExerciseId())) {
                runByTeam.put(r.getTeamId(), r);
            }
        }
        for (WorkshopTeam team : teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId)) {
            WorkshopExerciseRun run = runByTeam.get(team.getId());
            if (run == null) {
                run = new WorkshopExerciseRun();
                run.setExerciseId(task.getExerciseId());
                run.setTeamId(team.getId());
            }
            if (!run.getDeals().containsKey(taskId.toString())) {
                run.getDeals().put(taskId.toString(), CardDealing.deal(task.getConfig()));
                runs.save(run);
            }
        }
        return assignments(orgId, workshopId);
    }

    // ------------------------------------------------------------ analytics

    @Transactional(readOnly = true)
    public WorkshopAnalyticsResponse analytics(UUID orgId, UUID workshopId) {
        requireWorkshop(orgId, workshopId);
        List<WorkshopAnalyticsResponse.Row> rows = submissions.findAnalytics(workshopId).stream()
                .map(r -> new WorkshopAnalyticsResponse.Row(
                        r.getId(), r.getExerciseTitle(), r.getTaskTitle(), r.getTaskType(),
                        "LEAD".equals(r.getAssignee()) ? "Team lead" : "Team member",
                        r.getUserName(), r.getTeamName(),
                        "SORT".equals(r.getTaskType()) ? r.getAttempts() : null,
                        r.getElapsedMs(), r.getCompletedAt()))
                .toList();
        long totalMs = rows.stream().mapToLong(r -> r.elapsedMs() == null ? 0 : r.elapsedMs()).sum();
        return new WorkshopAnalyticsResponse(rows.size(), totalMs,
                rows.isEmpty() ? 0 : totalMs / rows.size(), rows);
    }

    // ------------------------------------------------------------ live board

    /**
     * The live results board. Mirrors {@code MyWorkshopService}'s progression
     * semantics: a LEAD task is done when any submission of the team completed
     * it, a MEMBER task per user; the lead also performs MEMBER tasks; a
     * member's task is locked ("waiting") until the exercise's run is shared.
     * A team is in the LEAD phase (one merged card) while nobody but the lead
     * can act, and splits into per-member chips as soon as someone else can.
     */
    @Transactional(readOnly = true)
    public WorkshopLiveResponse live(UUID orgId, UUID workshopId) {
        requireWorkshop(orgId, workshopId);

        // The road: every task of every exercise, flattened in play order.
        List<WorkshopExercise> exs = exercises.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId);
        List<UUID> exIds = exs.stream().map(WorkshopExercise::getId).toList();
        Map<UUID, List<WorkshopExerciseTask>> byEx = new LinkedHashMap<>();
        exs.forEach(e -> byEx.put(e.getId(), new ArrayList<>()));
        if (!exIds.isEmpty()) {
            tasks.findByExerciseIdInOrderByPositionAscCreatedAtAsc(exIds)
                    .forEach(t -> byEx.get(t.getExerciseId()).add(t));
        }
        List<WorkshopLiveResponse.StepDto> steps = new ArrayList<>();
        List<WorkshopExerciseTask> road = new ArrayList<>();
        Set<UUID> gatedExercises = new HashSet<>(); // exercises with LEAD tasks — members wait for the share
        for (WorkshopExercise e : exs) {
            for (WorkshopExerciseTask t : byEx.get(e.getId())) {
                road.add(t);
                steps.add(new WorkshopLiveResponse.StepDto(t.getId(), t.getTitle(),
                        t.getTaskType().name(), t.getAssignee().name(), e.getId(), e.getTitle()));
                if (t.getAssignee() == WorkshopTaskAssignee.LEAD) {
                    gatedExercises.add(e.getId());
                }
            }
        }

        Set<String> doneByTeam = new HashSet<>(); // taskId|teamId — LEAD tasks (one per team)
        Set<String> doneByUser = new HashSet<>(); // taskId|teamId|userId — MEMBER tasks
        for (WorkshopTaskSubmissionRepository.CompletionRow row : submissions.findCompletions(workshopId)) {
            doneByTeam.add(row.getTaskId() + "|" + row.getTeamId());
            doneByUser.add(row.getTaskId() + "|" + row.getTeamId() + "|" + row.getUserId());
        }
        Set<String> shared = new HashSet<>(); // exerciseId|teamId
        for (WorkshopExerciseRun r : runs.findByWorkshopId(workshopId)) {
            if (r.getSharedAt() != null) {
                shared.add(r.getExerciseId() + "|" + r.getTeamId());
            }
        }

        // Members per team, name order — matches the Teams tab, so chip numbers are stable.
        Map<UUID, List<WorkshopTeamRepository.WorkshopMemberRow>> membersByTeam = new LinkedHashMap<>();
        for (WorkshopTeamRepository.WorkshopMemberRow row : teams.findOrgMembers(orgId, workshopId)) {
            if (row.getTeamId() != null) {
                membersByTeam.computeIfAbsent(row.getTeamId(), k -> new ArrayList<>()).add(row);
            }
        }

        List<WorkshopLiveResponse.TeamDto> teamDtos = new ArrayList<>();
        for (WorkshopTeam team : teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId)) {
            List<WorkshopLiveResponse.ParticipantDto> participants = new ArrayList<>();
            Integer leadStep = null;
            boolean split = false; // someone besides the lead can act right now
            int number = 0;
            for (WorkshopTeamRepository.WorkshopMemberRow m : membersByTeam.getOrDefault(team.getId(), List.of())) {
                number++;
                Integer cur = null;
                for (int i = 0; i < road.size(); i++) {
                    WorkshopExerciseTask t = road.get(i);
                    if (!m.getLead() && t.getAssignee() != WorkshopTaskAssignee.MEMBER) {
                        continue;
                    }
                    boolean done = t.getAssignee() == WorkshopTaskAssignee.LEAD
                            ? doneByTeam.contains(t.getId() + "|" + team.getId())
                            : doneByUser.contains(t.getId() + "|" + team.getId() + "|" + m.getId());
                    if (!done) {
                        cur = i;
                        break;
                    }
                }
                boolean waiting = cur != null && !m.getLead()
                        && gatedExercises.contains(road.get(cur).getExerciseId())
                        && !shared.contains(road.get(cur).getExerciseId() + "|" + team.getId());
                if (m.getLead()) {
                    leadStep = cur;
                } else if (cur != null && !waiting) {
                    split = true;
                }
                participants.add(new WorkshopLiveResponse.ParticipantDto(
                        m.getId(), m.getName(), number, m.getLead(), cur, waiting));
            }

            boolean allDone = !participants.isEmpty()
                    && participants.stream().allMatch(p -> p.stepIndex() == null);
            String phase = allDone ? "DONE" : split ? "MEMBER" : "LEAD";
            teamDtos.add(new WorkshopLiveResponse.TeamDto(
                    team.getId(), team.getName(), team.getCard(), phase,
                    allDone ? null : leadStep != null ? leadStep : 0,
                    team.getHelpRequestedAt(), participants));
        }
        return new WorkshopLiveResponse(steps, teamDtos);
    }

    // ------------------------------------------------------------ helpers

    Workshop requireWorkshop(UUID orgId, UUID workshopId) {
        Workshop w = workshops.findById(workshopId)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop", workshopId.toString()));
        if (!w.getOrgId().equals(orgId)) {
            throw new ResourceNotFoundException("Workshop", workshopId.toString());
        }
        return w;
    }

    /**
     * Publishing is one-way (DRAFT → ACTIVE); published workshops flip freely
     * between ACTIVE and FINISHED but never return to DRAFT.
     */
    private void validateStatusChange(Workshop w, WorkshopStatus next) {
        if (next == WorkshopStatus.DRAFT && w.getStatus() != WorkshopStatus.DRAFT) {
            throw new IllegalOperationException("A published workshop cannot go back to draft");
        }
        if (next == WorkshopStatus.FINISHED && w.getStatus() == WorkshopStatus.DRAFT) {
            throw new IllegalOperationException("Publish the workshop before finishing it");
        }
    }

    /** Structural edits (exercises, tasks) are draft-only — publishing freezes the builder. */
    private Workshop requireDraftWorkshop(UUID orgId, UUID workshopId) {
        Workshop w = requireWorkshop(orgId, workshopId);
        if (w.getStatus() != WorkshopStatus.DRAFT) {
            throw new IllegalOperationException(
                    "Cannot modify a " + w.getStatus() + " workshop — only drafts are editable");
        }
        return w;
    }

    /** All callers are builder mutations, so this also enforces draft-only editing. */
    private WorkshopExercise requireExercise(UUID orgId, UUID workshopId, UUID exerciseId) {
        requireDraftWorkshop(orgId, workshopId);
        WorkshopExercise e = exercises.findById(exerciseId)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", exerciseId.toString()));
        if (!e.getWorkshopId().equals(workshopId)) {
            throw new ResourceNotFoundException("Exercise", exerciseId.toString());
        }
        return e;
    }

    private WorkshopExerciseTask requireTask(UUID exerciseId, UUID taskId) {
        WorkshopExerciseTask t = tasks.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        if (!t.getExerciseId().equals(exerciseId)) {
            throw new ResourceNotFoundException("Task", taskId.toString());
        }
        return t;
    }

    /** Pipeline edits invalidate in-flight state: drop the exercise's runs + submissions. */
    private void resetExerciseRuns(UUID exerciseId) {
        submissions.deleteByExerciseId(exerciseId);
        runs.deleteByExerciseId(exerciseId);
    }

    private <T> void applyOrder(List<UUID> orderedIds, List<T> items,
                                java.util.function.Function<T, UUID> idOf,
                                java.util.function.ObjIntConsumer<T> setPos) {
        Map<UUID, T> byId = new java.util.HashMap<>();
        for (T item : items) {
            byId.put(idOf.apply(item), item);
        }
        if (orderedIds.size() != items.size() || !byId.keySet().equals(new java.util.HashSet<>(orderedIds))) {
            throw new BadRequestException("orderedIds must contain exactly the existing ids");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            setPos.accept(byId.get(orderedIds.get(i)), i);
        }
    }
}
