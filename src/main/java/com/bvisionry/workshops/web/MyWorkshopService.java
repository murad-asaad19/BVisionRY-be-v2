package com.bvisionry.workshops.web;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.WorkshopEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.workshops.domain.Workshop;
import com.bvisionry.workshops.domain.WorkshopExercise;
import com.bvisionry.workshops.domain.WorkshopExerciseRun;
import com.bvisionry.workshops.domain.WorkshopExerciseTask;
import com.bvisionry.workshops.domain.WorkshopStatus;
import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskSubmission;
import com.bvisionry.workshops.domain.WorkshopTeam;
import com.bvisionry.workshops.domain.WorkshopTaskType;
import com.bvisionry.workshops.dto.MyWorkshopDto;
import com.bvisionry.workshops.dto.PlayResponse;
import com.bvisionry.workshops.dto.RespondRequest;
import com.bvisionry.workshops.dto.SortResultDto;
import com.bvisionry.workshops.dto.SubmitSortRequest;
import com.bvisionry.workshops.dto.SubmitWeightsRequest;
import com.bvisionry.workshops.repository.WorkshopExerciseRepository;
import com.bvisionry.workshops.repository.WorkshopExerciseRunRepository;
import com.bvisionry.workshops.repository.WorkshopExerciseTaskRepository;
import com.bvisionry.workshops.repository.WorkshopRepository;
import com.bvisionry.workshops.repository.WorkshopTaskSubmissionRepository;
import com.bvisionry.workshops.repository.WorkshopTeamRepository;

import lombok.RequiredArgsConstructor;

/**
 * Learner-facing workshop play: the sequential exercise state machine.
 *
 * <p>Rules (mirrors the design handoff): the team lead performs LEAD tasks once
 * per team, in pipeline order; completing the exercise's last LEAD task sets
 * {@code sharedAt} on the run, unlocking that exercise's MEMBER tasks, which
 * each non-lead member answers individually. Exercises run in workshop order.
 * Auto-wiring is nearest-above by position within the exercise: WEIGHT scores
 * the "left" pile of the nearest SORT, TOP ranks the nearest WEIGHT, QUESTION
 * presents the nearest TOP. SORT grading is server-side; the answer key never
 * leaves this service, and after {@code retryAfter} failed attempts retries
 * narrow to the wrong cards only. SORT hands are dealt once per team —
 * a random, side-balanced {@code dealPerTeam} subset pinned on the run.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MyWorkshopService {

    private final CurrentUserAccessor currentUser;
    private final WorkshopRepository workshops;
    private final WorkshopTeamRepository teams;
    private final WorkshopExerciseRepository exercises;
    private final WorkshopExerciseTaskRepository tasks;
    private final WorkshopExerciseRunRepository runs;
    private final WorkshopTaskSubmissionRepository submissions;
    private final ApplicationEventPublisher eventPublisher;

    // ------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<MyWorkshopDto> myWorkshops() {
        CurrentUser cu = currentUser.require();
        if (cu.orgId() == null) {
            return List.of();
        }
        return workshops.findMyWorkshops(cu.orgId(), cu.userId()).stream()
                .map(r -> new MyWorkshopDto(r.getId(), r.getName(),
                        WorkshopStatus.valueOf(r.getStatus()), r.getTeamName(), r.getLead()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlayResponse play(UUID workshopId) {
        return buildPlay(load(workshopId));
    }

    // ------------------------------------------------------------ actions

    public PlayResponse start(UUID workshopId, UUID taskId) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        WorkshopExerciseTask task = requireCurrentTask(ctx, taskId);
        WorkshopExerciseRun run = runFor(ctx, task.getExerciseId(), true);
        if (task.getTaskType() == WorkshopTaskType.SORT
                && !run.getDeals().containsKey(task.getId().toString())) {
            run.getDeals().put(task.getId().toString(), CardDealing.deal(task.getConfig()));
            runs.save(run);
        }
        WorkshopTaskSubmission sub = subFor(ctx, task).orElseGet(() -> newSubmission(ctx, task));
        if (sub.getStartedAt() == null) {
            sub.setStartedAt(OffsetDateTime.now());
        }
        submissions.save(sub);
        return buildPlay(ctx);
    }

    public SortResultDto submitSort(UUID workshopId, UUID taskId, SubmitSortRequest req) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        WorkshopExerciseTask task = requireCurrentTask(ctx, taskId);
        if (task.getTaskType() != WorkshopTaskType.SORT) {
            throw new BadRequestException("Not a sort task");
        }
        WorkshopTaskSubmission sub = requireStarted(ctx, task);

        List<String> dealt = dealtIds(ctx, task);
        Map<String, String> correctBy = new LinkedHashMap<>();
        for (Map<String, Object> card : cards(task.getConfig())) {
            correctBy.put(str(card.get("id")), "right".equals(card.get("correct")) ? "right" : "left");
        }

        // Merge over previous choices so narrowed retries re-grade the full hand.
        @SuppressWarnings("unchecked")
        Map<String, String> sorted = new LinkedHashMap<>(
                (Map<String, String>) sub.getPayload().getOrDefault("sorted", Map.of()));
        for (Map.Entry<String, String> e : req.sorted().entrySet()) {
            if (!correctBy.containsKey(e.getKey()) || !dealt.contains(e.getKey())) {
                throw new BadRequestException("Unknown card: " + e.getKey());
            }
            if (!"left".equals(e.getValue()) && !"right".equals(e.getValue())) {
                throw new BadRequestException("Pile must be 'left' or 'right'");
            }
            sorted.put(e.getKey(), e.getValue());
        }

        List<String> wrongIds = dealt.stream()
                .filter(id -> !Objects.equals(sorted.get(id), correctBy.get(id)))
                .toList();
        int attempts = sub.getAttempts() + 1;
        sub.setAttempts(attempts);
        sub.setPayload(new LinkedHashMap<>(Map.of("sorted", sorted, "wrongIds", wrongIds)));
        boolean allCorrect = wrongIds.isEmpty();
        if (allCorrect) {
            complete(ctx, task, sub);
        }
        submissions.save(sub);
        int retryAfter = intOf(task.getConfig().get("retryAfter"), 3);
        return new SortResultDto(allCorrect, dealt.size() - wrongIds.size(), wrongIds.size(),
                dealt.size(), attempts, !allCorrect && attempts >= retryAfter);
    }

    public PlayResponse submitWeights(UUID workshopId, UUID taskId, SubmitWeightsRequest req) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        WorkshopExerciseTask task = taskOf(ctx, taskId);
        if (task.getTaskType() != WorkshopTaskType.WEIGHT) {
            throw new BadRequestException("Not a weighting task");
        }
        requirePerformer(ctx, task);
        WorkshopTaskSubmission sub = subFor(ctx, task).orElse(null);
        if (sub == null || sub.getStartedAt() == null) {
            requireCurrentTask(ctx, taskId);
            sub = requireStarted(ctx, task);
        } else if (sub.isCompleted()) {
            // Re-score ("Go back & re-score" from the top-cards view) — only
            // while the results haven't been shared with the team.
            WorkshopExerciseRun run = runFor(ctx, task.getExerciseId(), false);
            if (task.getAssignee() == WorkshopTaskAssignee.LEAD
                    && run != null && run.getSharedAt() != null) {
                throw new BadRequestException("Results are already shared with the team");
            }
        }

        Map<String, Integer> clamped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : req.weights().entrySet()) {
            int v = e.getValue() == null ? 0 : Math.max(0, Math.min(100, e.getValue()));
            clamped.put(e.getKey(), v);
        }
        sub.getPayload().put("weights", clamped);
        if (!sub.isCompleted()) {
            complete(ctx, task, sub);
        }
        submissions.save(sub);
        return buildPlay(ctx);
    }

    /** Completes a TOP task — "Continue", or "Submit & share" when it's the lead's last. */
    public PlayResponse completeTop(UUID workshopId, UUID taskId) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        WorkshopExerciseTask task = requireCurrentTask(ctx, taskId);
        if (task.getTaskType() != WorkshopTaskType.TOP) {
            throw new BadRequestException("Not a top-cards task");
        }
        WorkshopTaskSubmission sub = requireStarted(ctx, task);
        complete(ctx, task, sub);
        submissions.save(sub);
        return buildPlay(ctx);
    }

    /**
     * The team's "we need help" ping, fired from the overdue task timer.
     * Every ping refreshes the timestamp, so a repeat ping re-alerts the live
     * board even while the previous card is still up. The admin's dismiss
     * nulls the flag.
     */
    public PlayResponse requestHelp(UUID workshopId) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        ctx.team().setHelpRequestedAt(OffsetDateTime.now());
        teams.save(ctx.team());
        return buildPlay(ctx);
    }

    public PlayResponse respond(UUID workshopId, UUID taskId, RespondRequest req) {
        Ctx ctx = load(workshopId);
        requireActive(ctx);
        WorkshopExerciseTask task = taskOf(ctx, taskId);
        if (task.getTaskType() != WorkshopTaskType.QUESTION) {
            throw new BadRequestException("Not a question task");
        }
        requirePerformer(ctx, task);
        WorkshopTaskSubmission sub = subFor(ctx, task).orElse(null);
        if (sub == null || !sub.isCompleted()) {
            requireCurrentTask(ctx, taskId);
            sub = requireStarted(ctx, task);
        }
        // else: editing an existing response from the done screen — allowed.

        boolean known = computeTopRows(ctx, findPrev(ctx, task, WorkshopTaskType.TOP)).stream()
                .anyMatch(c -> c.id().equals(req.cardId()));
        if (!known) {
            throw new BadRequestException("Pick one of the shared cards");
        }
        sub.getPayload().put("cardId", req.cardId());
        sub.getPayload().put("text", req.text().trim());
        if (!sub.isCompleted()) {
            complete(ctx, task, sub);
        }
        submissions.save(sub);
        return buildPlay(ctx);
    }

    // ------------------------------------------------------------ context

    private record Ctx(CurrentUser cu, Workshop workshop, WorkshopTeam team, boolean lead,
                       List<WorkshopExercise> exercises,
                       Map<UUID, List<WorkshopExerciseTask>> tasksByExercise,
                       Map<UUID, List<WorkshopTaskSubmission>> subsByTask,
                       Map<UUID, WorkshopExerciseRun> runsByExercise) {

        WorkshopTaskAssignee role() {
            return lead ? WorkshopTaskAssignee.LEAD : WorkshopTaskAssignee.MEMBER;
        }

        UUID teamId() {
            return team.getId();
        }
    }

    private Ctx load(UUID workshopId) {
        CurrentUser cu = currentUser.require();
        Workshop w = workshops.findById(workshopId)
                .filter(x -> x.getOrgId().equals(cu.orgId()))
                // Drafts don't exist for members until published.
                .filter(x -> x.getStatus() != WorkshopStatus.DRAFT)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop", workshopId.toString()));
        var membership = teams.findMembership(workshopId, cu.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Workshop", workshopId.toString()));
        WorkshopTeam team = teams.findById(membership.getTeamId())
                .orElseThrow(() -> new ResourceNotFoundException("Workshop", workshopId.toString()));

        List<WorkshopExercise> exs = exercises.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId);
        Map<UUID, List<WorkshopExerciseTask>> byEx = new LinkedHashMap<>();
        exs.forEach(e -> byEx.put(e.getId(), new ArrayList<>()));
        List<UUID> exIds = exs.stream().map(WorkshopExercise::getId).toList();
        List<WorkshopExerciseTask> allTasks = exIds.isEmpty()
                ? List.of()
                : tasks.findByExerciseIdInOrderByPositionAscCreatedAtAsc(exIds);
        allTasks.forEach(t -> byEx.get(t.getExerciseId()).add(t));

        Map<UUID, List<WorkshopTaskSubmission>> subsByTask = new LinkedHashMap<>();
        if (!allTasks.isEmpty()) {
            for (WorkshopTaskSubmission s : submissions.findByTeamIdAndTaskIdIn(
                    membership.getTeamId(), allTasks.stream().map(WorkshopExerciseTask::getId).toList())) {
                subsByTask.computeIfAbsent(s.getTaskId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<UUID, WorkshopExerciseRun> runsByEx = new LinkedHashMap<>();
        for (WorkshopExerciseRun r : runs.findByTeamId(membership.getTeamId())) {
            runsByEx.put(r.getExerciseId(), r);
        }
        return new Ctx(cu, w, team, membership.getLead(),
                exs, byEx, subsByTask, runsByEx);
    }

    /** My submission for a task: team-scoped for LEAD tasks, mine for MEMBER tasks. */
    private Optional<WorkshopTaskSubmission> subFor(Ctx ctx, WorkshopExerciseTask task) {
        List<WorkshopTaskSubmission> rows = ctx.subsByTask().getOrDefault(task.getId(), List.of());
        if (task.getAssignee() == WorkshopTaskAssignee.LEAD) {
            return rows.stream().findFirst();
        }
        return rows.stream().filter(s -> s.getUserId().equals(ctx.cu().userId())).findFirst();
    }

    private boolean isDone(Ctx ctx, WorkshopExerciseTask task) {
        return subFor(ctx, task).map(WorkshopTaskSubmission::isCompleted).orElse(false);
    }

    /**
     * Does this user perform this task? A member performs MEMBER tasks; a lead
     * performs their LEAD tasks first (to share) and then continues through the
     * MEMBER tasks like everyone else — pipeline order (sort/weight/top before
     * question) already puts the lead tasks first, so the "share, then do the
     * member task" flow falls out naturally.
     */
    private boolean performs(Ctx ctx, WorkshopExerciseTask task) {
        return task.getAssignee() == ctx.role()
                || (ctx.lead() && task.getAssignee() == WorkshopTaskAssignee.MEMBER);
    }

    /** The tasks of one exercise this user performs, in pipeline order. */
    private List<WorkshopExerciseTask> myTasksInExercise(Ctx ctx, UUID exerciseId) {
        return ctx.tasksByExercise().get(exerciseId).stream()
                .filter(t -> performs(ctx, t))
                .toList();
    }

    /** The first incomplete task I perform, in exercise order then pipeline order. */
    private WorkshopExerciseTask currentTask(Ctx ctx) {
        for (WorkshopExercise e : ctx.exercises()) {
            for (WorkshopExerciseTask t : ctx.tasksByExercise().get(e.getId())) {
                if (performs(ctx, t) && !isDone(ctx, t)) {
                    return t;
                }
            }
        }
        return null;
    }

    private boolean isShared(Ctx ctx, UUID exerciseId) {
        boolean hasLeadTasks = ctx.tasksByExercise().get(exerciseId).stream()
                .anyMatch(t -> t.getAssignee() == WorkshopTaskAssignee.LEAD);
        if (!hasLeadTasks) {
            return true;
        }
        WorkshopExerciseRun run = ctx.runsByExercise().get(exerciseId);
        return run != null && run.getSharedAt() != null;
    }

    // ------------------------------------------------------------ play view

    private PlayResponse buildPlay(Ctx ctx) {
        String role = ctx.lead() ? "LEAD" : "MEMBER";
        boolean finished = ctx.workshop().getStatus() == WorkshopStatus.FINISHED;

        // Pre-workshop survey gate: a member must complete the paired intro
        // survey before any tasks unlock. Skipped once the workshop is finished
        // (the survey window is over) — then we fall through to the thank-you.
        UUID preSurveyId = ctx.workshop().getPreWorkshopSurveyId();
        if (!finished && preSurveyId != null
                && !workshops.hasIntroSurveyResponse(
                        preSurveyId, ctx.workshop().getId(), ctx.cu().userId())) {
            return new PlayResponse(
                    ctx.workshop().getId(), ctx.workshop().getName(), ctx.workshop().getStatus(),
                    role, ctx.teamId(), ctx.team().getName(),
                    ctx.team().getCard(), ctx.team().getPosition(), "PRE_SURVEY",
                    ctx.team().getHelpRequestedAt(), null, null, List.of(), null);
        }

        WorkshopExerciseTask current = finished ? null : currentTask(ctx);

        String view;
        PlayResponse.ExerciseInfo exerciseInfo = null;
        PlayResponse.TaskView taskView = null;
        List<PlayResponse.RecapRow> recap = buildRecap(ctx);
        PlayResponse.ThankYou thankYou = null;

        if (finished) {
            view = "FINISHED";
            thankYou = thankYou(ctx);
        } else if (current == null) {
            view = ctx.lead() ? "LEAD_DONE" : "MEMBER_DONE";
            thankYou = thankYou(ctx);
        } else {
            WorkshopExercise exercise = ctx.exercises().stream()
                    .filter(e -> e.getId().equals(current.getExerciseId()))
                    .findFirst().orElseThrow();
            exerciseInfo = new PlayResponse.ExerciseInfo(
                    exercise.getId(), exercise.getTitle(),
                    ctx.exercises().indexOf(exercise) + 1, ctx.exercises().size());
            if (!ctx.lead() && !isShared(ctx, exercise.getId())) {
                view = "WAITING";
            } else {
                view = "TASK";
                taskView = buildTaskView(ctx, exercise, current);
            }
        }

        return new PlayResponse(
                ctx.workshop().getId(), ctx.workshop().getName(), ctx.workshop().getStatus(),
                role, ctx.teamId(), ctx.team().getName(),
                ctx.team().getCard(), ctx.team().getPosition(), view,
                ctx.team().getHelpRequestedAt(), exerciseInfo, taskView, recap, thankYou);
    }

    private PlayResponse.TaskView buildTaskView(Ctx ctx, WorkshopExercise exercise, WorkshopExerciseTask task) {
        List<WorkshopExerciseTask> pipeline = ctx.tasksByExercise().get(exercise.getId());
        List<WorkshopExerciseTask> myQueue = myTasksInExercise(ctx, exercise.getId());
        Map<String, Object> cfg = task.getConfig();
        WorkshopTaskSubmission sub = subFor(ctx, task).orElse(null);

        List<String> steps = List.of(str(cfg.getOrDefault("steps", "")).split("\\r?\\n")).stream()
                .filter(s -> !s.isBlank()).toList();

        String type = task.getTaskType().name();
        List<PlayResponse.CardDto> cards = null;
        PlayResponse.SortGate gate = null;
        String instructions = null;
        String leftLabel = null;
        String rightLabel = null;
        List<PlayResponse.ScoredCard> weightRows = null;
        List<PlayResponse.ScoredCard> topRows = null;
        boolean lastLeadTask = false;
        UUID sourceWeightTaskId = null;
        List<PlayResponse.ScoredCard> sourceWeightRows = null;
        String prompt = null;
        PlayResponse.ResponseDto response = null;

        switch (task.getTaskType()) {
            case SORT -> {
                instructions = str(cfg.getOrDefault("instructions", ""));
                leftLabel = str(cfg.getOrDefault("leftLabel", "Left"));
                rightLabel = str(cfg.getOrDefault("rightLabel", "Right"));
                List<String> dealt = dealtIds(ctx, task);
                if (sub != null && sub.getAttempts() > 0 && !sub.isCompleted()) {
                    List<String> wrong = strList(sub.getPayload().get("wrongIds"));
                    boolean narrowed = sub.getAttempts() >= intOf(cfg.get("retryAfter"), 3);
                    gate = new PlayResponse.SortGate(sub.getAttempts(),
                            dealt.size() - wrong.size(), wrong.size(), dealt.size(), narrowed);
                    cards = cardDtos(cfg, narrowed ? wrong : dealt);
                } else {
                    cards = cardDtos(cfg, dealt);
                }
            }
            case WEIGHT -> weightRows = weightRowsOf(ctx, task);
            case TOP -> {
                topRows = computeTopRows(ctx, task);
                WorkshopExerciseTask weightSrc = findPrev(ctx, task, WorkshopTaskType.WEIGHT);
                if (weightSrc != null) {
                    sourceWeightTaskId = weightSrc.getId();
                    sourceWeightRows = weightRowsOf(ctx, weightSrc);
                }
                // "Submit & share" shows when this is the last unfinished LEAD
                // task — the share trigger — even though the lead still has the
                // member tasks ahead of them.
                lastLeadTask = task.getAssignee() == WorkshopTaskAssignee.LEAD
                        && pipeline.stream()
                                .filter(t -> t.getAssignee() == WorkshopTaskAssignee.LEAD && !isDone(ctx, t))
                                .count() == 1;
            }
            case QUESTION -> {
                prompt = str(cfg.getOrDefault("prompt", ""));
                topRows = computeTopRows(ctx, findPrev(ctx, task, WorkshopTaskType.TOP));
                if (sub != null && sub.getPayload().get("cardId") != null) {
                    response = new PlayResponse.ResponseDto(
                            str(sub.getPayload().get("cardId")), str(sub.getPayload().get("text")));
                }
            }
        }

        int taskNo = myQueue.indexOf(task) + 1;
        return new PlayResponse.TaskView(
                task.getId(), type, task.getTitle(),
                pipeline.indexOf(task), pipeline.size(), taskNo, myQueue.size(),
                steps,
                str(cfg.getOrDefault("envTitle", task.getTitle())),
                str(cfg.getOrDefault("envText", "")),
                str(cfg.getOrDefault("celebrate", "Task complete!")),
                sub == null ? null : sub.getStartedAt(),
                intOf(cfg.get("durationMin"), 0) > 0 ? intOf(cfg.get("durationMin"), 0) : null,
                instructions, leftLabel, rightLabel, cards, gate,
                weightRows, topRows, lastLeadTask, sourceWeightTaskId, sourceWeightRows,
                prompt, response);
    }

    /**
     * The done-screen recap of my own work. For every task I performed and
     * completed: a TOP task becomes a ranked-list row (the lead's shared
     * deliverable, no free-text answer); a QUESTION task becomes an answer row
     * (the card I picked + my response), editable while the workshop is active.
     * Because the lead now also runs the member tasks, their recap naturally
     * carries both their shared list and their own answer.
     */
    private List<PlayResponse.RecapRow> buildRecap(Ctx ctx) {
        List<PlayResponse.RecapRow> out = new ArrayList<>();
        for (WorkshopExercise e : ctx.exercises()) {
            for (WorkshopExerciseTask t : ctx.tasksByExercise().get(e.getId())) {
                if (!performs(ctx, t) || !isDone(ctx, t)) {
                    continue;
                }
                if (t.getTaskType() == WorkshopTaskType.TOP && ctx.lead()) {
                    out.add(new PlayResponse.RecapRow(t.getId(), t.getTitle(),
                            "", null, "", "", computeTopRows(ctx, t)));
                } else if (t.getTaskType() == WorkshopTaskType.QUESTION) {
                    WorkshopTaskSubmission sub = subFor(ctx, t).orElse(null);
                    if (sub == null) {
                        continue;
                    }
                    List<PlayResponse.ScoredCard> topRows =
                            computeTopRows(ctx, findPrev(ctx, t, WorkshopTaskType.TOP));
                    String cardId = str(sub.getPayload().get("cardId"));
                    String cardText = topRows.stream()
                            .filter(c -> c.id().equals(cardId))
                            .map(PlayResponse.ScoredCard::text)
                            .findFirst().orElse("");
                    out.add(new PlayResponse.RecapRow(t.getId(), t.getTitle(),
                            str(t.getConfig().getOrDefault("prompt", "")),
                            cardId, cardText, str(sub.getPayload().get("text")), topRows));
                }
            }
        }
        return out;
    }

    private PlayResponse.ThankYou thankYou(Ctx ctx) {
        UUID surveyId = ctx.workshop().getPostCompletionSurveyId();
        if (surveyId != null) {
            return workshops.findPublishedSurvey(surveyId)
                    .map(s -> new PlayResponse.ThankYou(s.getName(), s.getPublicToken()))
                    .orElse(new PlayResponse.ThankYou(null, null));
        }
        return new PlayResponse.ThankYou(null, null);
    }

    // ------------------------------------------------------------ wiring

    /** Nearest task of {@code type} above {@code task} in the same exercise. */
    private WorkshopExerciseTask findPrev(Ctx ctx, WorkshopExerciseTask task, WorkshopTaskType type) {
        List<WorkshopExerciseTask> pipeline = ctx.tasksByExercise().get(task.getExerciseId());
        for (int i = pipeline.indexOf(task) - 1; i >= 0; i--) {
            if (pipeline.get(i).getTaskType() == type) {
                return pipeline.get(i);
            }
        }
        return null;
    }

    /** The WEIGHT task's rows: the dealt left-pile of the nearest SORT above, with current scores. */
    private List<PlayResponse.ScoredCard> weightRowsOf(Ctx ctx, WorkshopExerciseTask weightTask) {
        WorkshopExerciseTask sortTask = findPrev(ctx, weightTask, WorkshopTaskType.SORT);
        if (sortTask == null) {
            return List.of();
        }
        Map<String, Integer> weights = weightsOf(ctx, weightTask);
        return leftPile(ctx, sortTask).stream()
                .map(c -> new PlayResponse.ScoredCard(c.id(), c.text(), weights.getOrDefault(c.id(), 0)))
                .toList();
    }

    /** The TOP task's ranked rows: left pile scored by the nearest WEIGHT, top {@code count}. */
    private List<PlayResponse.ScoredCard> computeTopRows(Ctx ctx, WorkshopExerciseTask topTask) {
        if (topTask == null) {
            return List.of();
        }
        WorkshopExerciseTask weightTask = findPrev(ctx, topTask, WorkshopTaskType.WEIGHT);
        WorkshopExerciseTask sortTask = weightTask != null
                ? findPrev(ctx, weightTask, WorkshopTaskType.SORT)
                : findPrev(ctx, topTask, WorkshopTaskType.SORT);
        if (sortTask == null) {
            return List.of();
        }
        Map<String, Integer> weights = weightTask == null ? Map.of() : weightsOf(ctx, weightTask);
        return leftPile(ctx, sortTask).stream()
                .map(c -> new PlayResponse.ScoredCard(c.id(), c.text(), weights.getOrDefault(c.id(), 0)))
                .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
                .limit(intOf(topTask.getConfig().get("count"), 5))
                .toList();
    }

    /** Dealt cards the performer sorted into the left pile, in dealt order. */
    private List<PlayResponse.CardDto> leftPile(Ctx ctx, WorkshopExerciseTask sortTask) {
        Map<String, String> sorted = subFor(ctx, sortTask)
                .map(s -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> m = (Map<String, String>) s.getPayload()
                            .getOrDefault("sorted", Map.of());
                    return m;
                })
                .orElse(Map.of());
        return cardDtos(sortTask.getConfig(), dealtIds(ctx, sortTask)).stream()
                .filter(c -> "left".equals(sorted.get(c.id())))
                .toList();
    }

    private Map<String, Integer> weightsOf(Ctx ctx, WorkshopExerciseTask weightTask) {
        return subFor(ctx, weightTask)
                .map(s -> {
                    Map<String, Integer> out = new LinkedHashMap<>();
                    Object raw = s.getPayload().get("weights");
                    if (raw instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> out.put(str(k), intOf(v, 0)));
                    }
                    return out;
                })
                .orElseGet(LinkedHashMap::new);
    }

    // ------------------------------------------------------------ dealing

    /** This team's dealt hand for a SORT task (all pool cards until dealt). */
    private List<String> dealtIds(Ctx ctx, WorkshopExerciseTask sortTask) {
        WorkshopExerciseRun run = ctx.runsByExercise().get(sortTask.getExerciseId());
        if (run != null) {
            Object dealt = run.getDeals().get(sortTask.getId().toString());
            if (dealt != null) {
                return strList(dealt);
            }
        }
        return cards(sortTask.getConfig()).stream().map(c -> str(c.get("id"))).toList();
    }

    // ------------------------------------------------------------ mutations

    private void requireActive(Ctx ctx) {
        if (ctx.workshop().getStatus() != WorkshopStatus.ACTIVE) {
            throw new BadRequestException("This workshop has finished");
        }
    }

    private WorkshopExerciseTask taskOf(Ctx ctx, UUID taskId) {
        return ctx.tasksByExercise().values().stream()
                .flatMap(List::stream)
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
    }

    /**
     * The caller must actually perform this task. Guards the re-score / edit
     * paths of {@code submitWeights}/{@code respond}, which resolve the task via
     * {@code taskOf} and deliberately skip {@code requireCurrentTask} on the
     * already-started/completed branches — without this, a non-lead member could
     * write to (or complete) a LEAD task's shared submission.
     */
    private void requirePerformer(Ctx ctx, WorkshopExerciseTask task) {
        if (!performs(ctx, task)) {
            throw new BadRequestException("This is not your task");
        }
    }

    /** The task must be my role's current task, and (for members) already shared. */
    private WorkshopExerciseTask requireCurrentTask(Ctx ctx, UUID taskId) {
        WorkshopExerciseTask current = currentTask(ctx);
        if (current == null || !current.getId().equals(taskId)) {
            throw new BadRequestException("This is not your current task");
        }
        if (!ctx.lead() && !isShared(ctx, current.getExerciseId())) {
            throw new BadRequestException("Your team lead hasn't shared results yet");
        }
        return current;
    }

    private WorkshopTaskSubmission requireStarted(Ctx ctx, WorkshopExerciseTask task) {
        WorkshopTaskSubmission sub = subFor(ctx, task)
                .orElseThrow(() -> new BadRequestException("Start the task first"));
        if (sub.getStartedAt() == null) {
            throw new BadRequestException("Start the task first");
        }
        return sub;
    }

    private WorkshopTaskSubmission newSubmission(Ctx ctx, WorkshopExerciseTask task) {
        WorkshopTaskSubmission sub = new WorkshopTaskSubmission();
        sub.setTaskId(task.getId());
        sub.setTeamId(ctx.teamId());
        sub.setUserId(ctx.cu().userId());
        ctx.subsByTask().computeIfAbsent(task.getId(), k -> new ArrayList<>()).add(sub);
        return sub;
    }

    private WorkshopExerciseRun runFor(Ctx ctx, UUID exerciseId, boolean createIfMissing) {
        WorkshopExerciseRun run = ctx.runsByExercise().get(exerciseId);
        if (run == null && createIfMissing) {
            run = new WorkshopExerciseRun();
            run.setExerciseId(exerciseId);
            run.setTeamId(ctx.teamId());
            run = runs.saveAndFlush(run);
            ctx.runsByExercise().put(exerciseId, run);
        }
        return run;
    }

    /**
     * Marks the submission complete (stopping its timer) and — when this was
     * the lead's last task of the exercise — shares the run with the team.
     */
    private void complete(Ctx ctx, WorkshopExerciseTask task, WorkshopTaskSubmission sub) {
        OffsetDateTime now = OffsetDateTime.now();
        sub.setCompletedAt(now);
        if (sub.getStartedAt() != null) {
            sub.setElapsedMs(Duration.between(sub.getStartedAt(), now).toMillis());
        }
        if (task.getAssignee() != WorkshopTaskAssignee.LEAD) {
            return;
        }
        boolean allLeadDone = ctx.tasksByExercise().get(task.getExerciseId()).stream()
                .filter(t -> t.getAssignee() == WorkshopTaskAssignee.LEAD)
                .allMatch(t -> t.getId().equals(task.getId()) || isDone(ctx, t));
        if (allLeadDone) {
            WorkshopExerciseRun run = runFor(ctx, task.getExerciseId(), true);
            if (run.getSharedAt() == null) {
                run.setSharedAt(now);
                runs.save(run);
                notifyResultsShared(ctx, task.getExerciseId());
            }
        }
    }

    /** Nudge the non-lead members: their tasks just unlocked (sent AFTER_COMMIT). */
    private void notifyResultsShared(Ctx ctx, UUID exerciseId) {
        List<UUID> memberIds = teams.findNonLeadMemberIds(ctx.teamId());
        if (memberIds.isEmpty()) {
            return;
        }
        String exerciseTitle = ctx.exercises().stream()
                .filter(e -> e.getId().equals(exerciseId))
                .map(WorkshopExercise::getTitle)
                .findFirst().orElse("");
        eventPublisher.publishEvent(new WorkshopEvents.ResultsShared(
                ctx.workshop().getId(), ctx.workshop().getName(), exerciseTitle, memberIds));
    }

    // ------------------------------------------------------------ config plumbing

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cards(Map<String, Object> cfg) {
        Object raw = cfg.get("cards");
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<PlayResponse.CardDto> cardDtos(Map<String, Object> cfg, List<String> ids) {
        Map<String, String> textById = new LinkedHashMap<>();
        for (Map<String, Object> c : cards(cfg)) {
            textById.put(str(c.get("id")), str(c.get("text")));
        }
        return ids.stream()
                .filter(textById::containsKey)
                .map(id -> new PlayResponse.CardDto(id, textById.get(id)))
                .toList();
    }

    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(MyWorkshopService::str).toList();
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
