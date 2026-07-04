package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramSubmission;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.dto.GamificationDto;
import com.bvisionry.programflow.dto.JourneyResponse;
import com.bvisionry.programflow.dto.JourneyResponse.JourneyModule;
import com.bvisionry.programflow.dto.JourneyResponse.JourneyTask;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;
import com.bvisionry.programflow.dto.LeaderboardResponse;
import com.bvisionry.programflow.dto.PlayerResponse;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.SaveAnswersResponse;
import com.bvisionry.programflow.dto.SubmitResponse;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/** Learner-facing program flow: journey, task player, autosave, submit, leaderboard. */
@Service
@RequiredArgsConstructor
@Transactional
public class MyProgramService {

    private final ProgramModuleRepository modules;
    private final ProgramTaskRepository tasks;
    private final ProgramSubmissionRepository submissions;
    private final ProgramSettingsRepository settings;
    private final TeamRepository teams;
    private final CurrentUserAccessor currentUser;

    // ---------------------------------------------------------------- journey

    @Transactional(readOnly = true)
    public JourneyResponse journey() {
        Context ctx = context();
        ProgramSettingsDto s = settingsOf(ctx.orgId());

        List<JourneyModule> journeyModules = new java.util.ArrayList<>();
        int done = 0;
        int total = 0;
        for (int i = 0; i < ctx.visibleModules().size(); i++) {
            ProgramModule m = ctx.visibleModules().get(i);
            LockState lock = ProgramRules.lockState(ctx.visibleModules(), i, s.dripEnabled(),
                    ctx.submittedTaskIds(), OffsetDateTime.now());
            List<JourneyTask> journeyTasks = ProgramRules.liveTasks(m).stream().map(t -> {
                ProgramSubmission sub = ctx.myByTask().get(t.getId());
                int steps = t.getFields().size();
                int questions = (int) t.getFields().stream().filter(f -> f.getFieldType().answerable()).count();
                return new JourneyTask(t.getId(), t.getName(), t.getDueDate(), questions, steps,
                        sub == null ? null : sub.getStatus());
            }).toList();
            done += (int) journeyTasks.stream().filter(t -> t.myStatus() == SubmissionStatus.SUBMITTED).count();
            total += journeyTasks.size();
            journeyModules.add(new JourneyModule(m.getId(), m.getName(), m.getSummary(), lock, m.getUnlockAt(),
                    i > 0 ? ctx.visibleModules().get(i - 1).getName() : null, journeyTasks));
        }

        return new JourneyResponse(s, new JourneyResponse.Progress(done, total),
                gamification(ctx.mySubmissions()), journeyModules);
    }

    // ----------------------------------------------------------------- player

    @Transactional(readOnly = true)
    public PlayerResponse player(UUID taskId) {
        Access access = requireAccess(taskId);
        ProgramSubmission sub = submissions
                .findByTaskIdAndUserId(taskId, access.ctx().userId()).orElse(null);
        ProgramTask t = access.task();
        ProgramSettingsDto s = settingsOf(access.ctx().orgId());
        return new PlayerResponse(
                t.getId(), t.getName(), t.getDueDate(),
                access.module().getId(), access.module().getName(), access.moduleIndex() + 1,
                s.stageLabel(), s.dueSoonDays(),
                t.getFields().stream().map(ProgramMapper::toDto).toList(),
                sub == null ? Map.of() : sub.getAnswers(),
                sub == null ? null : sub.getStatus(),
                sub == null ? null : sub.getSavedAt(),
                sub == null ? null : sub.getSubmittedAt());
    }

    /**
     * The task the current learner may coach on: the same access rule as the
     * player (org + LIVE + audience + drip), with fields loaded in this
     * transaction. Exposed for {@link ProgramAiService}'s coach endpoint.
     */
    @Transactional(readOnly = true)
    public ProgramTask requirePlayableTask(UUID taskId) {
        return requireAccess(taskId).task();
    }

    public SaveAnswersResponse saveAnswers(UUID taskId, Map<String, Object> answers) {
        Access access = requireAccess(taskId);
        ProgramSubmission sub = submissions.findByTaskIdAndUserId(taskId, access.ctx().userId())
                .orElseGet(() -> {
                    ProgramSubmission created = new ProgramSubmission();
                    created.setTaskId(taskId);
                    created.setUserId(access.ctx().userId());
                    return created;
                });
        sub.setAnswers(new LinkedHashMap<>(answers));
        sub.setSavedAt(OffsetDateTime.now());
        sub = submissions.save(sub);
        return new SaveAnswersResponse(sub.getSavedAt());
    }

    public SubmitResponse submit(UUID taskId, Map<String, Object> answers) {
        Access access = requireAccess(taskId);
        ProgramTask t = access.task();

        List<UUID> missing = ProgramRules.missingRequired(t.getFields(), answers);
        if (!missing.isEmpty()) {
            throw new BadRequestException(missing.size() + " required answer"
                    + (missing.size() > 1 ? "s are" : " is") + " still missing");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ProgramSubmission sub = submissions.findByTaskIdAndUserId(taskId, access.ctx().userId())
                .orElseGet(() -> {
                    ProgramSubmission created = new ProgramSubmission();
                    created.setTaskId(taskId);
                    created.setUserId(access.ctx().userId());
                    return created;
                });

        boolean firstSubmit = sub.getSubmittedAt() == null;
        sub.setAnswers(new LinkedHashMap<>(answers));
        sub.setStatus(SubmissionStatus.SUBMITTED);
        sub.setSavedAt(now);
        int earned = 0;
        if (firstSubmit) {
            boolean onTime = t.getDueDate() == null || !LocalDate.now().isAfter(t.getDueDate());
            earned = GamificationDto.POINTS_PER_SUBMIT + (onTime ? GamificationDto.ON_TIME_BONUS : 0);
            sub.setSubmittedAt(now);
            sub.setPointsAwarded(earned);
        }
        submissions.save(sub);

        int answerable = (int) t.getFields().stream().filter(f -> f.getFieldType().answerable()).count();
        int answered = (int) t.getFields().stream()
                .filter(f -> f.getFieldType().answerable())
                .filter(f -> ProgramRules.isAnswered(f, answers.get(f.getId().toString())))
                .count();

        ProgramTask next = nextTask(access, taskId);
        return new SubmitResponse(earned, sub.getSubmittedAt(), answered, answerable,
                next == null ? null : next.getId(), next == null ? null : next.getName());
    }

    // ------------------------------------------------------------ leaderboard

    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard() {
        Context ctx = context();
        ProgramSettingsDto s = settingsOf(ctx.orgId());
        List<OrgMemberRow> members = teams.findOrgMembers(ctx.orgId());
        Map<UUID, String> teamNames = teams.findByOrgIdOrderByCreatedAtAsc(ctx.orgId()).stream()
                .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));

        List<UUID> orgTaskIds = modules.findByOrgIdOrderByPositionAsc(ctx.orgId()).stream()
                .flatMap(m -> m.getTasks().stream())
                .map(ProgramTask::getId)
                .toList();
        Map<UUID, List<ProgramSubmission>> byUser = orgTaskIds.isEmpty()
                ? Map.of()
                : submissions.findByTaskIdIn(orgTaskIds).stream()
                        .collect(Collectors.groupingBy(ProgramSubmission::getUserId));

        List<LeaderboardResponse.Row> rows = members.stream().map(m -> {
            List<ProgramSubmission> mine = byUser.getOrDefault(m.getId(), List.of());
            GamificationDto g = gamification(mine);
            return new LeaderboardResponse.Row(m.getId(), m.getName(),
                    m.getTeamId() == null ? null : teamNames.get(m.getTeamId()),
                    g.points(), g.streak(), m.getId().equals(ctx.userId()));
        }).sorted((a, b) -> Integer.compare(b.points(), a.points())).toList();

        return new LeaderboardResponse(s.endLabel(), s.endAt(), rows);
    }

    @Transactional(readOnly = true)
    public GamificationDto myGamification() {
        return gamification(submissions.findByUserId(currentUser.require().userId()));
    }

    // ---------------------------------------------------------------- helpers

    private static GamificationDto gamification(List<ProgramSubmission> mine) {
        int points = mine.stream().mapToInt(ProgramSubmission::getPointsAwarded).sum();
        int streak = ProgramRules.streak(
                mine.stream().map(ProgramSubmission::getSubmittedAt).filter(java.util.Objects::nonNull).toList(),
                LocalDate.now());
        return new GamificationDto(points, streak, GamificationDto.levelFor(points));
    }

    private ProgramSettingsDto settingsOf(UUID orgId) {
        return ProgramMapper.toDto(orgId == null ? null : settings.findById(orgId).orElse(null));
    }

    /** The learner's visible modules and submissions, loaded once per request. */
    private record Context(UUID userId, UUID orgId, List<ProgramModule> visibleModules,
            List<ProgramSubmission> mySubmissions, Map<UUID, ProgramSubmission> myByTask,
            Set<UUID> submittedTaskIds) {
    }

    private Context context() {
        CurrentUser user = currentUser.require();
        if (user.orgId() == null) {
            return new Context(user.userId(), null, List.of(), List.of(), Map.of(), Set.of());
        }
        final UUID myTeamId = resolveTeamId(user.orgId(), user.userId());
        List<ProgramModule> visible = modules.findByOrgIdOrderByPositionAsc(user.orgId()).stream()
                .filter(m -> ProgramRules.includes(m, user.userId(), myTeamId))
                .toList();
        List<ProgramSubmission> mine = submissions.findByUserId(user.userId());
        Map<UUID, ProgramSubmission> byTask = mine.stream()
                .collect(Collectors.toMap(ProgramSubmission::getTaskId, s -> s));
        Set<UUID> submitted = mine.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED)
                .map(ProgramSubmission::getTaskId)
                .collect(Collectors.toSet());
        return new Context(user.userId(), user.orgId(), visible, mine, byTask, submitted);
    }

    /** A stream findFirst() would NPE on a null team id, so use a plain loop. */
    private UUID resolveTeamId(UUID orgId, UUID userId) {
        for (OrgMemberRow member : teams.findOrgMembers(orgId)) {
            if (member.getId().equals(userId)) {
                return member.getTeamId();
            }
        }
        return null;
    }

    private record Access(Context ctx, ProgramTask task, ProgramModule module, int moduleIndex) {
    }

    /**
     * Loads the task and verifies the learner may work on it: LIVE, in their
     * org, in a module whose audience includes them and whose drip is unlocked.
     */
    private Access requireAccess(UUID taskId) {
        Context ctx = context();
        ProgramTask t = tasks.findWithModule(taskId)
                .filter(x -> x.getStatus() == ProgramTaskStatus.LIVE)
                .filter(x -> x.getModule().getOrgId().equals(ctx.orgId()))
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));

        int index = -1;
        for (int i = 0; i < ctx.visibleModules().size(); i++) {
            if (ctx.visibleModules().get(i).getId().equals(t.getModule().getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ResourceNotFoundException("Task", taskId.toString());
        }
        boolean dripEnabled = settingsOf(ctx.orgId()).dripEnabled();
        LockState lock = ProgramRules.lockState(ctx.visibleModules(), index, dripEnabled,
                ctx.submittedTaskIds(), OffsetDateTime.now());
        if (lock != LockState.UNLOCKED) {
            throw new BadRequestException("This module hasn't unlocked yet");
        }
        return new Access(ctx, t, ctx.visibleModules().get(index), index);
    }

    /** The next task to continue with: first non-submitted LIVE task in an unlocked module. */
    private ProgramTask nextTask(Access access, UUID justSubmittedTaskId) {
        Context ctx = access.ctx();
        boolean dripEnabled = settingsOf(ctx.orgId()).dripEnabled();
        Set<UUID> submitted = new java.util.HashSet<>(ctx.submittedTaskIds());
        submitted.add(justSubmittedTaskId);
        for (int i = 0; i < ctx.visibleModules().size(); i++) {
            LockState lock = ProgramRules.lockState(ctx.visibleModules(), i, dripEnabled, submitted,
                    OffsetDateTime.now());
            if (lock != LockState.UNLOCKED) {
                continue;
            }
            for (ProgramTask t : ProgramRules.liveTasks(ctx.visibleModules().get(i))) {
                if (!submitted.contains(t.getId())) {
                    return t;
                }
            }
        }
        return null;
    }
}
