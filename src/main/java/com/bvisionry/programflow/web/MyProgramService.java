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
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.domain.SubmissionStatus;
import com.bvisionry.programflow.dto.DirectAssignmentDto;
import com.bvisionry.programflow.dto.GamificationDto;
import com.bvisionry.programflow.dto.JourneyResponse;
import com.bvisionry.programflow.dto.JourneyResponse.JourneyModule;
import com.bvisionry.programflow.dto.JourneyResponse.JourneyTask;
import com.bvisionry.programflow.dto.JourneyResponse.LockState;
import com.bvisionry.programflow.dto.JourneyTaskState;
import com.bvisionry.programflow.dto.LeaderboardResponse;
import com.bvisionry.programflow.dto.LearnerCohortDto;
import com.bvisionry.programflow.dto.OpenTaskResponse;
import com.bvisionry.programflow.dto.PlayerResponse;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.SaveAnswersResponse;
import com.bvisionry.programflow.dto.SubmitResponse;
import com.bvisionry.programflow.repository.CohortMemberRow;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;
import com.bvisionry.programflow.repository.TaskSpineRepository;

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
    private final TaskSpineRepository spine;
    private final com.bvisionry.common.coursevisibility.CourseVisibilityAccess courseVisibility;
    private final CurrentUserAccessor currentUser;
    private final ApplicationEventPublisher eventPublisher;

    // --------------------------------------------------------------- cohorts

    /** The cohorts the current learner is enrolled in (LAUNCHED first; DRAFT/ARCHIVED invisible), for the switcher. */
    @Transactional(readOnly = true)
    public List<LearnerCohortDto> myCohorts() {
        return cohorts.findEnrolled(currentUser.require().userId()).stream()
                .map(LearnerCohortDto::of).toList();
    }

    // ---------------------------------------------------------------- journey

    @Transactional(readOnly = true)
    public JourneyResponse journey(UUID cohortId) {
        CurrentUser me = currentUser.require();
        return buildJourney(me.userId(), me.orgId(), resolveCohort(me.userId(), cohortId));
    }

    /**
     * Read-only journey of ANOTHER member for the shared founder profile
     * (redesign spec §2.4 — "read-only reuse of the member's Journey").
     * CALLERS AUTHORIZE FIRST (org guard stack or {@code CoachAccess}); this
     * method's own tenancy contribution is the org filter on the enrolled
     * cohorts, so a foreign org id can only ever produce the empty journey.
     * Cohort choice mirrors {@link #resolveCohort}: an explicit request must
     * be one of the member's enrolled cohorts (else 404); null defaults to
     * the first enrolled. ANY lifecycle status: this is a staff door, and
     * reviewing existing work must not 404 because the cohort is temporarily
     * unlaunched — the member-only visibility rule lives in
     * {@code CohortRepository.findEnrolled}.
     */
    @Transactional(readOnly = true)
    public JourneyResponse journeyOfMember(UUID orgId, UUID memberId, UUID requestedCohortId) {
        // Spec §13: enrolment (the roster) is the truth — cohorts are platform
        // artifacts, so there is no per-cohort org to filter on. The orgId is
        // the viewed member's org and scopes their direct assignments and
        // course visibility below.
        List<Cohort> enrolled = cohorts.findEnrolledForStaff(memberId);
        Cohort cohort;
        if (requestedCohortId != null) {
            cohort = enrolled.stream()
                    .filter(c -> c.getId().equals(requestedCohortId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Cohort", requestedCohortId.toString()));
        } else {
            cohort = enrolled.isEmpty() ? null : enrolled.get(0);
        }
        return buildJourney(memberId, orgId, cohort);
    }

    private JourneyResponse buildJourney(UUID userId, UUID orgId, Cohort cohort) {
        List<DirectAssignmentDto> direct = directAssignments(orgId, userId);
        if (cohort == null) {
            // Spec §2.1: no cohort → the page is Direct assignments only.
            return new JourneyResponse(ProgramSettingsDto.defaults(), new JourneyResponse.Progress(0, 0),
                    gamification(List.of()), List.of(), null, 0, direct);
        }
        Context ctx = context(userId, orgId, cohort);
        ProgramSettingsDto s = settingsOf(cohort.getId());
        Set<UUID> blockedCourses = blockedCourseIds(orgId, ctx.visibleModules());

        List<JourneyModule> journeyModules = new ArrayList<>();
        int done = 0;
        int total = 0;
        // The payoff "before" (spec §2.1/§5): walking modules in board order,
        // each evaluated milestone's score becomes the next milestone's before.
        java.math.BigDecimal previousMilestoneScore = null;
        for (int i = 0; i < ctx.visibleModules().size(); i++) {
            ProgramModule m = ctx.visibleModules().get(i);
            LockState lock = ProgramRules.lockState(ctx.visibleModules(), i, s.dripEnabled(),
                    ctx.doneTaskIds(), blockedCourses, OffsetDateTime.now());
            List<JourneyTask> journeyTasks = new ArrayList<>();
            for (ProgramTask t : ProgramRules.liveTasks(m)) {
                JourneyTask row = journeyTask(t, ctx, previousMilestoneScore, blockedCourses);
                if (t.getTaskType() == ProgramTaskType.ASSESSMENT && row.score() != null) {
                    previousMilestoneScore = row.score();
                }
                // A task that does not GATE renders but counts in neither side of
                // the progress fraction (ProgramRules.gates — the one predicate).
                if (ProgramRules.gates(t, blockedCourses)) {
                    if (ProgramRules.done(row.state())) {
                        done++;
                    }
                    total++;
                }
                journeyTasks.add(row);
            }
            journeyModules.add(new JourneyModule(m.getId(), m.getName(), m.getSummary(), m.isPaced(),
                    lock, m.getUnlockAt(),
                    i > 0 ? ctx.visibleModules().get(i - 1).getName() : null, journeyTasks));
        }

        // Spec §3/§10: a REQUIRED course assigned outside the cohort (org rule or
        // direct) gates journey progress too — that is what "required" buys the
        // admin. Optional ones display and never gate. Cohort COURSE tasks are
        // already counted above; the task is the gate there.
        for (DirectAssignmentDto d : direct) {
            if (d.taskType() == ProgramTaskType.COURSE && d.required()) {
                total++;
                if (ProgramRules.done(d.state())) {
                    done++;
                }
            }
        }

        return new JourneyResponse(s, new JourneyResponse.Progress(done, total),
                gamification(ctx.mySubmissions()), journeyModules,
                cohort.getId(),
                // Active roster only — the same filter the leaderboard uses, so the
                // hero's "N founders in your cohort" matches the list a member sees.
                cohorts.countRoster(cohort.getId()), direct);
    }

    /** One typed journey row: LESSON keeps the legacy fields; other types read their slice. */
    private JourneyTask journeyTask(ProgramTask t, Context ctx,
            java.math.BigDecimal previousMilestoneScore, Set<UUID> blockedCourses) {
        if (t.getTaskType() == ProgramTaskType.LESSON) {
            ProgramSubmission sub = ctx.myByTask().get(t.getId());
            int steps = t.getFields().size();
            int questions = (int) t.getFields().stream()
                    .filter(f -> f.getFieldType().answerable()).count();
            return new JourneyTask(t.getId(), t.getName(), t.getDueDate(), questions, steps,
                    sub == null ? null : sub.getStatus(),
                    ProgramTaskType.LESSON, null, null,
                    ProgramRules.lessonState(sub == null ? null : sub.getStatus()),
                    null, null, null,
                    sub == null || sub.getSubmittedAt() == null ? null : sub.getSubmittedAt().toInstant(),
                    null, false);
        }
        TypedState ts = ctx.typedStates().getOrDefault(t.getId(), TypedState.NOT_STARTED);
        return new JourneyTask(t.getId(), t.getName(), t.getDueDate(), 0, 0, null,
                t.getTaskType(), t.getRefId(), t.getMilestoneRole(), ts.state(),
                ts.progressPct(), ts.score(),
                t.getTaskType() == ProgramTaskType.ASSESSMENT ? previousMilestoneScore : null,
                ts.submittedAt(), ts.completedAt(),
                t.getTaskType() == ProgramTaskType.COURSE && t.getRefId() != null
                        && blockedCourses.contains(t.getRefId()));
    }

    /**
     * COURSE task refs the cohort's org may no longer SEE (spec §3). One batched
     * query per journey/board render; empty when the org has none.
     */
    Set<UUID> blockedCourseIds(UUID orgId, List<ProgramModule> modules) {
        Set<UUID> refs = modules.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() == ProgramTaskType.COURSE && t.getRefId() != null)
                .map(ProgramTask::getRefId)
                .collect(java.util.stream.Collectors.toSet());
        return courseVisibility.invisibleCourseIds(orgId, refs);
    }

    /* ------------------------------------------------- direct assignments */

    /** The member's work not attached to any cohort task (spec §2.1). */
    private List<DirectAssignmentDto> directAssignments(UUID orgId, UUID userId) {
        if (orgId == null) {
            return List.of();
        }
        List<DirectAssignmentDto> rows = new ArrayList<>();
        for (var e : spine.directExercises(orgId, userId)) {
            rows.add(new DirectAssignmentDto(e.assignmentId(), ProgramTaskType.EXERCISE,
                    e.templateId(), e.submissionId(), e.title(),
                    ProgramRules.exerciseState(e.status()), null, null,
                    e.deadline(), e.assignedAt(), e.submittedAt(), e.reviewedAt(), null, false));
        }
        for (var a : spine.directAssessments(orgId, userId)) {
            rows.add(new DirectAssignmentDto(a.assignmentId(), ProgramTaskType.ASSESSMENT,
                    a.pipelineId(), a.submissionId(), a.title(),
                    ProgramRules.assessmentState(a.status()), null, a.score(),
                    a.deadline(), a.assignedAt(), a.submittedAt(), a.evaluatedAt(), null, false));
        }
        for (var c : spine.directCourses(userId)) {
            // A rule-derived row has no enrollment yet, so status is null and
            // ProgramRules.courseState maps it to NOT_STARTED — exactly right:
            // the member has the course and has not started it. The row appears
            // when they open it (TaskSpineRepository#ensureEnrollment).
            rows.add(new DirectAssignmentDto(c.enrollmentId(), ProgramTaskType.COURSE,
                    c.courseId(), c.courseId(), c.title(),
                    ProgramRules.courseState(c.status()), c.progressPct(), null,
                    c.deadline(), c.enrolledAt(), null, c.completedAt(),
                    c.source(), c.required()));
        }
        return rows;
    }

    // ----------------------------------------------------------------- player

    @Transactional(readOnly = true)
    public PlayerResponse player(UUID taskId) {
        Access access = requireAccess(taskId);
        requireLesson(access.task());
        ProgramSubmission sub = submissions
                .findByTaskIdAndUserId(taskId, access.ctx().userId()).orElse(null);
        ProgramTask t = access.task();
        ProgramSettingsDto s = settingsOf(access.ctx().cohort().getId());
        return new PlayerResponse(
                t.getId(), t.getName(), t.getDueDate(),
                access.module().getId(), access.module().getName(),
                stageNumber(access.ctx().visibleModules(), access.moduleIndex()),
                s.stageLabel(), s.dueSoonDays(),
                t.getFields().stream().map(ProgramMapper::toDto).toList(),
                sub == null ? Map.of() : sub.getAnswers(),
                sub == null ? null : sub.getStatus(),
                sub == null ? null : sub.getSavedAt(),
                sub == null ? null : sub.getSubmittedAt(),
                // The member's OWN player is never read-only — readOnly is the
                // peer-view flag (playerOfMember below).
                false);
    }

    /**
     * Read-only player of ANOTHER member's LESSON task for the shared founder
     * profile (spec §2.4 — the Work tab's per-type view). CALLERS AUTHORIZE
     * FIRST (org guard stack or {@code CoachAccess}) — same stance as
     * {@link #journeyOfMember}; this method's own contribution is "the member
     * is enrolled in the task's cohort", so a foreign task id 404s without
     * leaking existence. No audience/drip re-check: staff review of submitted
     * work must not be blocked by a lock the member has since passed — and the
     * cohort's lifecycle state is not part of that gate either
     * ({@code findEnrolledForStaff}), so an unlaunched cohort's work stays
     * reviewable.
     */
    @Transactional(readOnly = true)
    public PlayerResponse playerOfMember(UUID memberId, UUID taskId) {
        ProgramTask t = tasks.findWithModule(taskId)
                .filter(x -> x.getStatus() == ProgramTaskStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        requireLesson(t);
        Cohort cohort = cohorts.findEnrolledForStaff(memberId).stream()
                .filter(c -> c.getId().equals(t.getModule().getCohortId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        // Stage number over the modules VISIBLE TO THIS MEMBER, exactly as
        // player() does via ctx.visibleModules() (ProgramRules.includes) — an
        // earlier module the member's audience excludes is not their Week N, and
        // counting all modules drifts this staff view's "Week N" from what the
        // member sees for the same task.
        List<ProgramModule> visible = modules.findByCohortIdOrderByPositionAsc(cohort.getId()).stream()
                .filter(m -> ProgramRules.includes(m, memberId))
                .toList();
        int index = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).getId().equals(t.getModule().getId())) {
                index = i;
                break;
            }
        }
        ProgramSubmission sub = submissions.findByTaskIdAndUserId(taskId, memberId).orElse(null);
        ProgramSettingsDto s = settingsOf(cohort.getId());
        return new PlayerResponse(
                t.getId(), t.getName(), t.getDueDate(),
                t.getModule().getId(), t.getModule().getName(), stageNumber(visible, index),
                s.stageLabel(), s.dueSoonDays(),
                t.getFields().stream().map(ProgramMapper::toDto).toList(),
                sub == null ? Map.of() : sub.getAnswers(),
                sub == null ? null : sub.getStatus(),
                sub == null ? null : sub.getSavedAt(),
                sub == null ? null : sub.getSubmittedAt(),
                true);
    }

    /**
     * The task the current learner may coach on: the same access rule as the
     * player (cohort + LIVE + audience + drip), with fields loaded in this
     * transaction. Exposed for {@link ProgramAiService}'s coach endpoint.
     */
    @Transactional(readOnly = true)
    public ProgramTask requirePlayableTask(UUID taskId) {
        ProgramTask t = requireAccess(taskId).task();
        requireLesson(t);
        return t;
    }

    public SaveAnswersResponse saveAnswers(UUID taskId, Map<String, Object> answers) {
        Access access = requireAccess(taskId);
        requireLesson(access.task());
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
        requireLesson(access.task());
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
            // The bell goes to the SUBMITTING member's org admins (spec §13 —
            // the cohort spans orgs; each org hears about its own people).
            eventPublisher.publishEvent(new ProgramFlowEvents.TaskSubmitted(
                    access.ctx().orgId(), access.ctx().userId(), currentUser.require().name(), t.getName()));
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
        ProgramSettingsDto s = settingsOf(cohort.getId());
        // Spec §13.7 accepted consequence: a cross-org cohort's leaderboard
        // mixes the whole roster — members compete cohort-wide, labelled by org.
        List<CohortMemberRow> members = cohorts.findRoster(cohort.getId());

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
            return new LeaderboardResponse.Row(m.getId(), m.getName(), m.getOrgName(),
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
     * enrolled visible cohort (LAUNCHED first), or null when they have none.
     */
    private Cohort resolveCohort(UUID userId, UUID requestedCohortId) {
        List<Cohort> enrolled = cohorts.findEnrolled(userId);
        if (requestedCohortId != null) {
            return enrolled.stream().filter(c -> c.getId().equals(requestedCohortId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Cohort", requestedCohortId.toString()));
        }
        return enrolled.isEmpty() ? null : enrolled.get(0);
    }

    /**
     * A non-LESSON task's member state read from its owning slice (course
     * enrollment / exercise submission / tagged assessment submission /
     * survey participation).
     */
    record TypedState(JourneyTaskState state, Integer progressPct,
            java.math.BigDecimal score, java.time.Instant submittedAt,
            java.time.Instant completedAt) {

        static final TypedState NOT_STARTED =
                new TypedState(JourneyTaskState.NOT_STARTED, null, null, null, null);
    }

    /**
     * The learner's visible modules, submissions and typed-task states within
     * one cohort, loaded once per request. {@code doneTaskIds} counts every
     * task type (LESSON submitted, course completed, exercise submitted or
     * reviewed, …) — the drip lock and next-task cursor run on it.
     */
    private record Context(UUID userId, UUID orgId, Cohort cohort, List<ProgramModule> visibleModules,
            List<ProgramSubmission> mySubmissions, Map<UUID, ProgramSubmission> myByTask,
            Map<UUID, TypedState> typedStates, Set<UUID> doneTaskIds) {
    }

    /** {@code orgId} is the MEMBER's own org (spec §13) — it scopes course visibility and slice writes. */
    private Context context(UUID userId, UUID orgId, Cohort cohort) {
        List<ProgramModule> visible = modules.findByCohortIdOrderByPositionAsc(cohort.getId()).stream()
                .filter(m -> ProgramRules.includes(m, userId))
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

        List<ProgramTask> typedTasks = visible.stream()
                .flatMap(m -> ProgramRules.liveTasks(m).stream())
                .filter(t -> t.getTaskType() != ProgramTaskType.LESSON)
                .toList();
        Map<UUID, TypedState> typedStates = typedStates(List.of(userId), typedTasks)
                .getOrDefault(userId, Map.of());

        Set<UUID> done = new HashSet<>();
        mine.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED)
                .map(ProgramSubmission::getTaskId)
                .forEach(done::add);
        typedStates.forEach((taskId, ts) -> {
            if (ProgramRules.done(ts.state())) {
                done.add(taskId);
            }
        });
        return new Context(userId, orgId, cohort, visible, mine, byTask, typedStates, done);
    }

    /**
     * Batch cross-slice status read for non-LESSON tasks, reduced to the
     * unified state vocabulary — the admin pulse's door into the spine
     * (keyed user → task → state; absent = not started).
     */
    Map<UUID, Map<UUID, JourneyTaskState>> typedStatesForPulse(List<UUID> userIds,
            List<ProgramTask> typedTasks) {
        Map<UUID, Map<UUID, JourneyTaskState>> out = new LinkedHashMap<>();
        typedStates(userIds, typedTasks).forEach((userId, byTask) -> byTask.forEach(
                (taskId, ts) -> out.computeIfAbsent(userId, k -> new LinkedHashMap<>())
                        .put(taskId, ts.state())));
        return out;
    }

    /** Package-private: the cohort-board matrix reads full typed states (score + stamps). */
    Map<UUID, Map<UUID, TypedState>> typedStates(List<UUID> userIds,
            List<ProgramTask> typedTasks) {
        Map<UUID, Map<UUID, TypedState>> byUser = new LinkedHashMap<>();
        if (userIds.isEmpty() || typedTasks.isEmpty()) {
            return byUser;
        }
        Map<ProgramTaskType, List<ProgramTask>> byType = typedTasks.stream()
                .filter(t -> t.getRefId() != null)
                .collect(Collectors.groupingBy(ProgramTask::getTaskType));

        // COURSE — enrollment per (user, course); several tasks may share a ref.
        List<ProgramTask> courses = byType.getOrDefault(ProgramTaskType.COURSE, List.of());
        if (!courses.isEmpty()) {
            var rows = spine.courseStates(userIds, refs(courses));
            for (var r : rows) {
                TypedState ts = new TypedState(ProgramRules.courseState(r.status()),
                        r.progressPct(), null, null, r.completedAt());
                forTasksWithRef(courses, r.courseId(),
                        taskId -> put(byUser, r.userId(), taskId, ts));
            }
        }
        // EXERCISE — keyed by TASK id (the assignment tag, V173), not by
        // template: two cohorts handing out the same exercise each own their
        // own copy of the work.
        List<ProgramTask> exercises = byType.getOrDefault(ProgramTaskType.EXERCISE, List.of());
        if (!exercises.isEmpty()) {
            for (var r : spine.exerciseStates(userIds, taskIds(exercises))) {
                put(byUser, r.userId(), r.taskId(),
                        new TypedState(ProgramRules.exerciseState(r.status()),
                                null, null, r.submittedAt(), r.reviewedAt()));
            }
        }
        // ASSESSMENT — keyed by TASK id (the submission tag), not by ref:
        // same-pipeline milestones stay distinguishable.
        List<ProgramTask> assessments = byType.getOrDefault(ProgramTaskType.ASSESSMENT, List.of());
        if (!assessments.isEmpty()) {
            for (var r : spine.assessmentStates(userIds, taskIds(assessments))) {
                put(byUser, r.userId(), r.taskId(), new TypedState(
                        ProgramRules.assessmentState(r.status()), null,
                        "EVALUATED".equals(r.status()) ? r.score() : null,
                        r.submittedAt(), r.evaluatedAt()));
            }
            // Pre-spine visibility (review decision #6): a BASELINE milestone
            // with NO tagged submission falls back to the member's EARLIEST
            // evaluated UNTAGGED submission of that pipeline — mirroring the
            // comparison slice's baseline resolution exactly, so the journey
            // band and the report agree. CHECKIN/DISTANCE stay tag-only.
            //
            // The fallback only ever adopts submissions that belong to NO
            // cohort task: since spec §13 a member can be in several cohorts
            // sharing a pipeline, and a submission tagged to one cohort's
            // milestone must never surface as another cohort's baseline (the
            // query enforces program_task_id IS NULL).
            List<ProgramTask> baselineTasks = assessments.stream()
                    .filter(t -> t.getMilestoneRole()
                            == com.bvisionry.programflow.domain.MilestoneRole.BASELINE)
                    .toList();
            for (var r : spine.earliestEvaluatedForPipelines(userIds, refs(baselineTasks))) {
                for (ProgramTask t : baselineTasks) {
                    if (r.pipelineId().equals(t.getRefId())
                            && !byUser.getOrDefault(r.userId(), Map.of()).containsKey(t.getId())) {
                        put(byUser, r.userId(), t.getId(), new TypedState(
                                JourneyTaskState.EVALUATED, null, r.score(),
                                r.submittedAt(), r.evaluatedAt()));
                    }
                }
            }
        }
        // SURVEY — keyed by TASK id (the response tag, V173), same reason.
        List<ProgramTask> surveys = byType.getOrDefault(ProgramTaskType.SURVEY, List.of());
        if (!surveys.isEmpty()) {
            for (var r : spine.surveyParticipation(userIds, taskIds(surveys))) {
                put(byUser, r.userId(), r.taskId(), new TypedState(JourneyTaskState.DONE,
                        null, null, r.completedAt(), r.completedAt()));
            }
        }
        return byUser;
    }

    private static List<UUID> refs(List<ProgramTask> tasks) {
        return tasks.stream().map(ProgramTask::getRefId).distinct().toList();
    }

    private static List<UUID> taskIds(List<ProgramTask> tasks) {
        return tasks.stream().map(ProgramTask::getId).toList();
    }

    /**
     * COURSE only: enrollment is one global row per (member, course) — the
     * operator's ruling that a course is per member — so several tasks may
     * share one state. Every other type resolves by its own task id.
     */
    private static void forTasksWithRef(List<ProgramTask> tasks, UUID refId,
            java.util.function.Consumer<UUID> taskIdConsumer) {
        for (ProgramTask t : tasks) {
            if (refId.equals(t.getRefId())) {
                taskIdConsumer.accept(t.getId());
            }
        }
    }

    private static void put(Map<UUID, Map<UUID, TypedState>> byUser, UUID userId, UUID taskId,
            TypedState state) {
        byUser.computeIfAbsent(userId, k -> new LinkedHashMap<>()).put(taskId, state);
    }

    private record Access(Context ctx, ProgramTask task, ProgramModule module, int moduleIndex) {
    }

    /**
     * Loads the task and verifies the learner may work on it: LIVE, in a cohort
     * they're enrolled in, in a module whose audience includes them and whose
     * drip is unlocked.
     */
    private Access requireAccess(UUID taskId) {
        CurrentUser me = currentUser.require();
        UUID userId = me.userId();
        ProgramTask t = tasks.findWithModule(taskId)
                .filter(x -> x.getStatus() == ProgramTaskStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
        Cohort cohort = resolveCohort(userId, t.getModule().getCohortId());
        Context ctx = context(userId, me.orgId(), cohort);

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
        boolean dripEnabled = settingsOf(cohort.getId()).dripEnabled();
        LockState lock = ProgramRules.lockState(ctx.visibleModules(), index, dripEnabled,
                ctx.doneTaskIds(),
                blockedCourseIds(ctx.orgId(), ctx.visibleModules()),
                OffsetDateTime.now());
        if (lock != LockState.UNLOCKED) {
            throw new BadRequestException("This module hasn't unlocked yet");
        }
        return new Access(ctx, t, ctx.visibleModules().get(index), index);
    }

    /**
     * The module's stage number for the player kicker: its 1-based position
     * counting ONLY paced modules, or 0 when the module is itself unpaced and
     * therefore has no stage. Position in the list is NOT the answer once a
     * cohort carries always-on material — an orientation section at the front
     * would push the first real week to "Week 02".
     */
    private static int stageNumber(List<ProgramModule> ordered, int index) {
        if (!ordered.get(index).isPaced()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i <= index; i++) {
            if (ordered.get(i).isPaced()) {
                n++;
            }
        }
        return n;
    }

    /** Lessons are the only tasks with an in-place form; other types open via {@link #open}. */
    private static void requireLesson(ProgramTask t) {
        if (t.getTaskType() != ProgramTaskType.LESSON) {
            throw new BadRequestException(
                    "This is a " + t.getTaskType().name().toLowerCase() + " task — open it instead.");
        }
    }

    /**
     * The idempotent open action (spec §2.1/§3/§7b): ensures the prerequisite
     * in the owning slice exists — COURSE enrollment, EXERCISE assignment +
     * working copy, ASSESSMENT assignment + submission TAGGED with this task —
     * and returns where to go. A COMPLETED cohort may still open work that
     * already exists (read-only review) but never spawns new prerequisites.
     */
    public OpenTaskResponse open(UUID taskId) {
        Access access = requireAccess(taskId);
        ProgramTask t = access.task();
        UUID userId = access.ctx().userId();
        UUID orgId = access.ctx().orgId();
        UUID target = switch (t.getTaskType()) {
            case LESSON -> t.getId();
            case SURVEY -> requireRef(t);
            case COURSE -> {
                // Spec §3 downgrade policy: no NEW content opens for a course the
                // org can no longer see. The journey row already says so; this is
                // the control behind it.
                if (!courseVisibility.isVisibleToUser(userId, requireRef(t))) {
                    throw new BadRequestException(
                            "This course is no longer available to your organization.");
                }
                if (!spine.enrollmentExists(userId, requireRef(t))) {
                    spine.ensureEnrollment(userId, t.getRefId());
                }
                yield t.getRefId();
            }
            case EXERCISE -> {
                UUID existing = t.getRefId() == null ? null
                        : spine.findExerciseSubmissionId(userId, t.getId(), t.getRefId()).orElse(null);
                if (existing != null) {
                    yield existing;
                }
                yield spine.ensureExerciseSubmission(orgId, requireRef(t), userId, t.getId());
            }
            // A cohort assessment REFERENCES the member's assessment history
            // rather than duplicating it: if they already sat this instrument —
            // before the cohort existed, or straight from their own assessment
            // list — that sitting IS the milestone. Only someone who has never
            // sat it gets a fresh one. Adopting claims the sitting with the tag
            // so every later read resolves it the same way without re-deriving.
            case ASSESSMENT -> spine.findTaggedSubmissionId(userId, t.getId())
                    .or(() -> spine.adoptableSubmissionId(userId, t.getId())
                            .map(adopted -> {
                                spine.tagSubmission(adopted, t.getId());
                                return adopted;
                            }))
                    .orElseGet(() ->
                        spine.createTaggedSubmission(orgId, requireRef(t), userId, t.getId()));
        };
        return new OpenTaskResponse(t.getId(), t.getTaskType(), t.getRefId(), target,
                java.time.Instant.now());
    }

    private static UUID requireRef(ProgramTask t) {
        if (t.getRefId() == null) {
            // Unreachable for LIVE tasks (validated at publish); defense in depth.
            throw new BadRequestException("This task has no reference configured yet.");
        }
        return t.getRefId();
    }

    /** The next task to continue with: first non-submitted LIVE task in an unlocked module. */
    private ProgramTask nextTask(Access access, UUID justSubmittedTaskId) {
        Context ctx = access.ctx();
        boolean dripEnabled = settingsOf(ctx.cohort().getId()).dripEnabled();
        Set<UUID> submitted = new HashSet<>(ctx.doneTaskIds());
        submitted.add(justSubmittedTaskId);
        Set<UUID> blockedCourses = blockedCourseIds(ctx.orgId(), ctx.visibleModules());
        for (int i = 0; i < ctx.visibleModules().size(); i++) {
            LockState lock = ProgramRules.lockState(ctx.visibleModules(), i, dripEnabled, submitted,
                    blockedCourses, OffsetDateTime.now());
            if (lock != LockState.UNLOCKED) {
                continue;
            }
            for (ProgramTask t : ProgramRules.liveTasks(ctx.visibleModules().get(i))) {
                // Never point the continue-cursor at a task the member cannot
                // act on — an uncompletable type, or a course their org can no
                // longer open. That's a dead end.
                if (ProgramRules.gates(t, blockedCourses) && !submitted.contains(t.getId())) {
                    return t;
                }
            }
        }
        return null;
    }
}
