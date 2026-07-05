package com.bvisionry.programflow.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
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
import com.bvisionry.programflow.dto.LearnerCohortDto;
import com.bvisionry.programflow.dto.PlayerResponse;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.SaveAnswersResponse;
import com.bvisionry.programflow.dto.SubmitResponse;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/** Learner-facing program flow: cohort switching, journey, task player, autosave, submit, leaderboard. */
@Service
@RequiredArgsConstructor
@Transactional
public class MyProgramService {

    private final CohortRepository cohorts;
    private final ProgramModuleRepository modules;
    private final ProgramTaskRepository tasks;
    private final ProgramSubmissionRepository submissions;
    private final ProgramSettingsRepository settings;
    private final TeamRepository teams;
    private final CurrentUserAccessor currentUser;
    private final ApplicationEventPublisher eventPublisher;

    // --------------------------------------------------------------- cohorts

    /** The cohorts the current learner is enrolled in (ACTIVE first), for the switcher. */
    @Transactional(readOnly = true)
    public List<LearnerCohortDto> myCohorts() {
        return cohorts.findEnrolled(currentUser.require().userId()).stream()
                .map(LearnerCohortDto::of).toList();
    }

    // ---------------------------------------------------------------- journey

    @Transactional(readOnly = true)
    public JourneyResponse journey(UUID cohortId) {
        UUID userId = currentUser.require().userId();
        Cohort cohort = resolveCohort(userId, cohortId);
        if (cohort == null) {
            return new JourneyResponse(ProgramSettingsDto.defaults(), new JourneyResponse.Progress(0, 0),
                    gamification(List.of()), List.of(), null, false);
        }
        Context ctx = context(userId, cohort);
        ProgramSettingsDto s = settingsOf(cohort.getId());

        List<JourneyModule> journeyModules = new ArrayList<>();
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
                gamification(ctx.mySubmissions()), journeyModules,
                cohort.getId(), cohort.getStatus() == CohortStatus.FINISHED);
    }

    // ----------------------------------------------------------------- player

    @Transactional(readOnly = true)
    public PlayerResponse player(UUID taskId) {
        Access access = requireAccess(taskId);
        ProgramSubmission sub = submissions
                .findByTaskIdAndUserId(taskId, access.ctx().userId()).orElse(null);
        ProgramTask t = access.task();
        ProgramSettingsDto s = settingsOf(access.ctx().cohort().getId());
        return new PlayerResponse(
                t.getId(), t.getName(), t.getDueDate(),
                access.module().getId(), access.module().getName(), access.moduleIndex() + 1,
                s.stageLabel(), s.dueSoonDays(),
                t.getFields().stream().map(ProgramMapper::toDto).toList(),
                sub == null ? Map.of() : sub.getAnswers(),
                sub == null ? null : sub.getStatus(),
                sub == null ? null : sub.getSavedAt(),
                sub == null ? null : sub.getSubmittedAt(),
                access.ctx().finished());
    }

    /**
     * The task the current learner may coach on: the same access rule as the
     * player (cohort + LIVE + audience + drip), with fields loaded in this
     * transaction. Exposed for {@link ProgramAiService}'s coach endpoint.
     */
    @Transactional(readOnly = true)
    public ProgramTask requirePlayableTask(UUID taskId) {
        return requireAccess(taskId).task();
    }

    public SaveAnswersResponse saveAnswers(UUID taskId, Map<String, Object> answers) {
        Access access = requireWritableAccess(taskId);
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
        Access access = requireWritableAccess(taskId);
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
            // Admin bell: only on the first submit — revisions stay quiet.
            eventPublisher.publishEvent(new ProgramFlowEvents.TaskSubmitted(
                    access.ctx().cohort().getOrgId(), currentUser.require().name(), t.getName()));
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
    public LeaderboardResponse leaderboard(UUID cohortId) {
        UUID userId = currentUser.require().userId();
        Cohort cohort = resolveCohort(userId, cohortId);
        if (cohort == null) {
            return new LeaderboardResponse(null, null, List.of());
        }
        UUID orgId = cohort.getOrgId();
        ProgramSettingsDto s = settingsOf(cohort.getId());
        Set<UUID> enrolled = new HashSet<>(cohort.getMemberIds());
        List<OrgMemberRow> members = teams.findOrgMembers(orgId).stream()
                .filter(m -> enrolled.contains(m.getId())).toList();
        Map<UUID, String> teamNames = teams.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));

        List<UUID> cohortTaskIds = modules.findByCohortIdOrderByPositionAsc(cohort.getId()).stream()
                .flatMap(m -> m.getTasks().stream())
                .map(ProgramTask::getId)
                .toList();
        Map<UUID, List<ProgramSubmission>> byUser = cohortTaskIds.isEmpty()
                ? Map.of()
                : submissions.findByTaskIdIn(cohortTaskIds).stream()
                        .collect(Collectors.groupingBy(ProgramSubmission::getUserId));

        List<LeaderboardResponse.Row> rows = members.stream().map(m -> {
            List<ProgramSubmission> mine = byUser.getOrDefault(m.getId(), List.of());
            GamificationDto g = gamification(mine);
            return new LeaderboardResponse.Row(m.getId(), m.getName(),
                    m.getTeamId() == null ? null : teamNames.get(m.getTeamId()),
                    g.points(), g.streak(), m.getId().equals(userId));
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

    private ProgramSettingsDto settingsOf(UUID cohortId) {
        return ProgramMapper.toDto(cohortId == null ? null : settings.findById(cohortId).orElse(null));
    }

    /**
     * Resolves which cohort a learner is looking at. A non-null request must be
     * one they're enrolled in (else 404); a null request defaults to their first
     * enrolled cohort (ACTIVE ones first), or null when they have none.
     */
    private Cohort resolveCohort(UUID userId, UUID requestedCohortId) {
        List<Cohort> enrolled = cohorts.findEnrolled(userId);
        if (requestedCohortId != null) {
            return enrolled.stream().filter(c -> c.getId().equals(requestedCohortId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Cohort", requestedCohortId.toString()));
        }
        return enrolled.isEmpty() ? null : enrolled.get(0);
    }

    /** The learner's visible modules and submissions within one cohort, loaded once per request. */
    private record Context(UUID userId, Cohort cohort, List<ProgramModule> visibleModules,
            List<ProgramSubmission> mySubmissions, Map<UUID, ProgramSubmission> myByTask,
            Set<UUID> submittedTaskIds) {

        boolean finished() {
            return cohort.getStatus() == CohortStatus.FINISHED;
        }
    }

    private Context context(UUID userId, Cohort cohort) {
        UUID myTeamId = resolveTeamId(cohort.getOrgId(), userId);
        List<ProgramModule> visible = modules.findByCohortIdOrderByPositionAsc(cohort.getId()).stream()
                .filter(m -> ProgramRules.includes(m, userId, myTeamId))
                .toList();
        Set<UUID> cohortTaskIds = visible.stream()
                .flatMap(m -> m.getTasks().stream())
                .map(ProgramTask::getId)
                .collect(Collectors.toSet());
        List<ProgramSubmission> mine = submissions.findByUserId(userId).stream()
                .filter(s -> cohortTaskIds.contains(s.getTaskId()))
                .toList();
        Map<UUID, ProgramSubmission> byTask = mine.stream()
                .collect(Collectors.toMap(ProgramSubmission::getTaskId, s -> s));
        Set<UUID> submitted = mine.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED)
                .map(ProgramSubmission::getTaskId)
                .collect(Collectors.toSet());
        return new Context(userId, cohort, visible, mine, byTask, submitted);
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

    /** Like {@link #requireAccess} but rejects writes to a FINISHED (read-only) cohort. */
    private Access requireWritableAccess(UUID taskId) {
        Access access = requireAccess(taskId);
        if (access.ctx().finished()) {
            throw new BadRequestException("This cohort has finished — it is read-only now.");
        }
        return access;
    }

    /**
     * Loads the task and verifies the learner may work on it: LIVE, in a cohort
     * they're enrolled in, in a module whose audience includes them and — unless
     * the cohort has finished (read-only review) — whose drip is unlocked.
     */
    private Access requireAccess(UUID taskId) {
        UUID userId = currentUser.require().userId();
        ProgramTask t = tasks.findWithModule(taskId)
                .filter(x -> x.getStatus() == ProgramTaskStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        Cohort cohort = resolveCohort(userId, t.getModule().getCohortId());
        Context ctx = context(userId, cohort);

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
        if (!ctx.finished()) {
            boolean dripEnabled = settingsOf(cohort.getId()).dripEnabled();
            LockState lock = ProgramRules.lockState(ctx.visibleModules(), index, dripEnabled,
                    ctx.submittedTaskIds(), OffsetDateTime.now());
            if (lock != LockState.UNLOCKED) {
                throw new BadRequestException("This module hasn't unlocked yet");
            }
        }
        return new Access(ctx, t, ctx.visibleModules().get(index), index);
    }

    /** The next task to continue with: first non-submitted LIVE task in an unlocked module. */
    private ProgramTask nextTask(Access access, UUID justSubmittedTaskId) {
        Context ctx = access.ctx();
        boolean dripEnabled = settingsOf(ctx.cohort().getId()).dripEnabled();
        Set<UUID> submitted = new HashSet<>(ctx.submittedTaskIds());
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
