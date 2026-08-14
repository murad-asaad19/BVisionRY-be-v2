package com.bvisionry.programflow.web;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.BoardSnapshot;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
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
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.PulseResponse;
import com.bvisionry.programflow.dto.PulseResponse.CellState;
import com.bvisionry.programflow.dto.PulseResponse.PulseColumn;
import com.bvisionry.programflow.dto.PulseResponse.PulseRow;
import com.bvisionry.programflow.dto.SaveBoardRequest;
import com.bvisionry.programflow.repository.BoardRestoreRepository;
import com.bvisionry.programflow.repository.CohortBoardReadRepository;
import com.bvisionry.programflow.repository.CohortMemberRow;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.bvisionry.programflow.repository.ProgramSubmissionRepository;
import com.bvisionry.programflow.repository.ProgramTaskRepository;

import jakarta.persistence.EntityManager;
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
    private final BoardRestoreRepository restore;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------ board

    @Transactional(readOnly = true)
    public BoardResponse getBoard(UUID cohortId) {
        Cohort cohort = cohortService.require(cohortId);
        List<CohortMemberRow> members = cohortFounders(cohortId);
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<ModuleDto> moduleDtos = mods.stream()
                .map(m -> ProgramMapper.toDto(m, reached(m, members)))
                .toList();
        int taskCount = mods.stream().mapToInt(m -> m.getTasks().size()).sum();
        return new BoardResponse(
                ProgramMapper.toDto(settings.findById(cohortId).orElse(null)),
                moduleDtos,
                cohort.getBoardVersion(),
                new BoardResponse.BoardStats(mods.size(), taskCount, members.size()));
    }

    /**
     * The Curriculum builder's Save, and the ONLY writer of a cohort's modules,
     * tasks and fields. Applies the WHOLE board in one write: everything is
     * upserted by its (possibly client-minted) id, anything the payload does not
     * hold is deleted, and positions are re-derived from list order.
     *
     * <p><strong>The payload is complete, so a stale one is destructive.</strong>
     * Whatever another admin added since this board was read is simply absent
     * from it — and absent means delete. So the save is conditional on
     * {@link SaveBoardRequest#expectedVersion()}: a mismatch is refused with a
     * 412 and the admin reloads. Being the single writer is what makes that
     * version trustworthy; there is no second endpoint that can move the board
     * without moving the version with it.
     *
     * @param req the board as the builder holds it; {@code force} confirms
     *     deleting — or retyping, which reverts progress just the same — tasks
     *     members have already worked on; without it such a save is refused
     *     with a 409 naming exactly what would be lost.
     */
    public BoardResponse saveBoard(UUID cohortId, SaveBoardRequest req) {
        Cohort cohort = cohortService.requireEditable(cohortId);
        // Fast-fail on an obviously stale payload, BEFORE the expensive validation
        // + restore — but this value check is not atomic on its own (board_version
        // is a plain column), so it is only the optimisation; the authoritative
        // guard is the conditional bump at the end. A rejected save must not move
        // the version, which is exactly why the bump sits after validation, not here.
        if (cohort.getBoardVersion() != req.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Someone else saved this board while you were editing it. "
                            + "Reload to pick up their changes — saving now would delete them.");
        }
        List<ProgramModule> current = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<CohortMemberRow> roster = cohorts.findRoster(cohortId);

        BoardSnapshot snapshot = toSnapshot(req);
        requireOwnIds(cohortId, snapshot);
        validateBoard(req, current, roster);

        List<BoardRestoreRepository.DoomedTask> atRisk =
                restore.memberWorkAtRisk(cohortId, snapshot);
        if (!req.force() && !atRisk.isEmpty()) {
            throw new IllegalOperationException(memberWorkMessage(atRisk));
        }
        List<ProgramFlowEvents.ModuleAssigned> assignments =
                audienceNotifications(cohort, req, current, roster);

        // Optimistic concurrency, ATOMIC and positioned after validation so a
        // rejected save never moves the version: bump only if it STILL equals what
        // the editor read. A concurrent save that slipped in since the read above
        // makes this match 0 rows and 412s (the loser) instead of both clobbering —
        // which the plain value check alone (board_version is not @Version) allows.
        // The bump lands with the raw-SQL restore in this one transaction.
        if (cohorts.bumpBoardVersionIfMatches(cohortId, req.expectedVersion()) == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Someone else saved this board while you were editing it. "
                            + "Reload to pick up their changes — saving now would delete them.");
        }
        restore.restore(cohortId, snapshot);
        entityManager.clear();
        // The curriculum IS the designation (spec §5) — re-derive AFTER the
        // clear, or the milestone tasks would be read back out of a first-level
        // cache the raw-SQL restore never touched.
        syncMilestonePair(cohortId);

        assignments.forEach(events::publishEvent);
        return getBoard(cohortId);
    }

    /**
     * "Saving would delete member work on “Pitch” (2 members). Save anyway to
     * discard it." — the 409 names what is at stake rather than asking the admin
     * to guess which of forty cards someone has already answered.
     */
    private static String memberWorkMessage(List<BoardRestoreRepository.DoomedTask> atRisk) {
        return "Saving would delete member work on "
                + atRisk.stream()
                        .map(t -> "“" + t.taskName() + "” (" + t.memberCount()
                                + (t.memberCount() == 1 ? " member" : " members") + ")")
                        .collect(Collectors.joining(", "))
                + ". Save anyway to discard it.";
    }

    /** The submitted board as the snapshot the restore speaks; new fields get an id here. */
    private static BoardSnapshot toSnapshot(SaveBoardRequest req) {
        return new BoardSnapshot(req.modules().stream()
                .map(m -> new BoardSnapshot.ModuleSnap(m.id(), m.name(), m.summary(),
                        blankToNull(m.pillarLabel()), m.paced(), m.lockMode(), m.unlockAt(),
                        m.assignMode(),
                        List.copyOf(m.memberIds()),
                        m.tasks().stream()
                                .map(t -> new BoardSnapshot.TaskSnap(t.id(), t.name(), t.taskType(),
                                        t.refId(), t.milestoneRole(), t.dueDate(), t.status(),
                                        t.aiDraft(), fieldSnaps(t.fields())))
                                .toList()))
                .toList());
    }

    private static List<BoardSnapshot.FieldSnap> fieldSnaps(List<FieldUpsert> fields) {
        return fields.stream()
                .map(f -> new BoardSnapshot.FieldSnap(
                        f.id() == null ? UUID.randomUUID() : f.id(),
                        f.type(), f.type().answerable() && f.required(),
                        new LinkedHashMap<>(f.config())))
                .toList();
    }

    /**
     * Every id the payload claims must be free or already this cohort's. The
     * restore upserts BY ID, so an id owned by another cohort would silently
     * move that row here, and the same id twice would silently drop a row.
     */
    private void requireOwnIds(UUID cohortId, BoardSnapshot snapshot) {
        List<UUID> moduleIds = snapshot.moduleIds();
        List<UUID> taskIds = snapshot.taskIds();
        List<UUID> fieldIds = snapshot.fieldIds();
        if (Set.copyOf(moduleIds).size() != moduleIds.size()
                || Set.copyOf(taskIds).size() != taskIds.size()
                || Set.copyOf(fieldIds).size() != fieldIds.size()) {
            throw new BadRequestException("This board carries the same id twice.");
        }
        List<UUID> foreign = restore.foreignIds(cohortId, moduleIds, taskIds, fieldIds);
        if (!foreign.isEmpty()) {
            throw new BadRequestException("This board references "
                    + foreign.size() + " id(s) that belong to another cohort.");
        }
    }

    /**
     * The name the builder puts on a new column so the column has something to
     * render before the admin types over it. Because it is SEEDED and not
     * typed, carrying it is no evidence anyone named the module — which is
     * exactly how it reached a founder's Journey (spec §12). So it is refused
     * outright rather than only when blank (blank the DTO's {@code @NotBlank}
     * already catches): an admin who genuinely wants those two words pays one
     * rename, while letting them through puts the builder's scaffolding in
     * front of every member of the cohort.
     */
    private static final String MODULE_PLACEHOLDER_NAME = "Untitled module";

    /**
     * The same argument as {@link #MODULE_PLACEHOLDER_NAME}, one level down. The
     * builder seeds every new card with {@code "Untitled <type> task"} so the
     * column has something to render, and its save payload used to substitute a
     * bare {@code "Untitled task"} for a name the admin left empty — both are
     * scaffolding, and both reached a founder's Journey looking like content.
     */
    private static boolean isTaskPlaceholderName(String name, ProgramTaskType type) {
        String trimmed = name == null ? "" : name.trim();
        // Locale.ROOT: type.name() is an ASCII enum constant, but a Turkish
        // default locale lower-cases "EXERCISE" to "exercıse" (dotless ı), so the
        // expected literal would stop matching the builder's own seeded name.
        return trimmed.equalsIgnoreCase("Untitled task")
                || trimmed.equalsIgnoreCase(
                        "Untitled " + type.name().toLowerCase(java.util.Locale.ROOT) + " task");
    }

    /**
     * Re-applies to the batch every rule a single-task save enforces, so "save
     * the whole board" cannot become a hole in the typed spine. The cohort-level
     * milestone pair is read across the SUBMITTED board rather than the DB —
     * after this write the payload IS the cohort, so moving the BASELINE role
     * from one task to another in one save is legal while two BASELINEs in one
     * payload are not.
     *
     * <p>Rejections name the offending task: on a forty-task board a bare
     * "pick what this references" is unactionable, which is why this throws a
     * composed message instead of the single-task save's per-field 400.
     */
    private void validateBoard(SaveBoardRequest req,
            List<ProgramModule> current, List<CohortMemberRow> roster) {
        Map<UUID, ProgramTask> existing = current.stream()
                .flatMap(m -> m.getTasks().stream())
                .collect(Collectors.toMap(ProgramTask::getId, Function.identity()));
        Set<UUID> rosterIds = roster.stream().map(CohortMemberRow::getId)
                .collect(Collectors.toSet());
        Map<MilestoneRole, UUID> milestones = new EnumMap<>(MilestoneRole.class);

        for (SaveBoardRequest.ModuleUpsert m : req.modules()) {
            if (MODULE_PLACEHOLDER_NAME.equalsIgnoreCase(blankToNull(m.name()))) {
                throw new BadRequestException("“" + MODULE_PLACEHOLDER_NAME + "” is the builder's "
                        + "placeholder, not a name — rename that module before saving, or members "
                        + "will see it on their journey.");
            }
            if (m.assignMode() == AudienceMode.MEMBERS && !rosterIds.containsAll(m.memberIds())) {
                throw new BadRequestException("One or more members are not enrolled in this cohort");
            }
            for (SaveBoardRequest.TaskUpsert t : m.tasks()) {
                if (isTaskPlaceholderName(t.name(), t.taskType())) {
                    throw new BadRequestException("“" + t.name().trim() + "” in “" + m.name()
                            + "” is the builder's placeholder, not a name — name that task before "
                            + "saving, or members will see it on their journey.");
                }
                Map<String, String> errors = taskSpineErrors(existing.get(t.id()), t.taskType(),
                        t.refId(), t.milestoneRole(), t.status(), t.fields().size());
                // Spec §5: at most ONE BASELINE and ONE DISTANCE per cohort —
                // the pair the distance comparison is computed on, so a second
                // one would make "the" baseline ambiguous.
                if (errors.isEmpty()
                        && (t.milestoneRole() == MilestoneRole.BASELINE
                                || t.milestoneRole() == MilestoneRole.DISTANCE)
                        && milestones.putIfAbsent(t.milestoneRole(), t.id()) != null) {
                    errors.put("milestoneRole", "This cohort already has a "
                            + t.milestoneRole().name().toLowerCase(java.util.Locale.ROOT)
                            + " milestone task.");
                }
                if (!errors.isEmpty()) {
                    throw new BadRequestException("“" + t.name() + "” — "
                            + String.join(" ", errors.values()));
                }
            }
        }
    }

    /**
     * "New module assigned" for the members a saved audience newly reaches —
     * {@link #updateAudience}'s notification, preserved for the batch save.
     * Only an EXISTING module can notify: one created by this save has no
     * "before", exactly as {@link #createModule} never notified either.
     */
    private List<ProgramFlowEvents.ModuleAssigned> audienceNotifications(Cohort cohort,
            SaveBoardRequest req, List<ProgramModule> current, List<CohortMemberRow> roster) {
        // A DRAFT's modules are invisible to members and a COMPLETED cohort is
        // read-only for them — same gate as the per-module write.
        if (cohort.getStatus() != CohortStatus.LAUNCHED) {
            return List.of();
        }
        Map<UUID, ProgramModule> before = current.stream()
                .collect(Collectors.toMap(ProgramModule::getId, Function.identity()));
        List<ProgramFlowEvents.ModuleAssigned> assignments = new ArrayList<>();
        for (SaveBoardRequest.ModuleUpsert m : req.modules()) {
            ProgramModule was = before.get(m.id());
            if (was == null) {
                continue;
            }
            Predicate<UUID> reachedNow = m.assignMode() == AudienceMode.ALL
                    ? id -> true
                    : Set.copyOf(m.memberIds())::contains;
            List<UUID> newlyAssigned = roster.stream()
                    .map(CohortMemberRow::getId)
                    .filter(reachedNow)
                    .filter(id -> !ProgramRules.includes(was, id))
                    .toList();
            if (!newlyAssigned.isEmpty()) {
                assignments.add(new ProgramFlowEvents.ModuleAssigned(
                        m.name(), cohort.getName(), newlyAssigned));
            }
        }
        return assignments;
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
        // The pair is CURRICULUM-DERIVED (see syncMilestonePair), so the
        // request's two pipeline ids are ignored — this endpoint is pacing, and
        // leaving the columns alone is what keeps it from being a second writer
        // that could desync them. Not re-derived here either: a cohort
        // designated before the milestone tasks existed still resolves its
        // sides by the evaluated-submission fallback, and quietly nulling that
        // because someone edited the due-soon threshold is data loss with no
        // signal. Such a cohort converges on its next board save, with the
        // Settings card naming the milestone it is missing.
        return ProgramMapper.toDto(settings.save(s));
    }

    /**
     * Makes the stored pair equal what the curriculum says (spec §5): the
     * BASELINE milestone task's pipeline is the baseline, the DISTANCE
     * milestone task's is the distance. There is no separate designation any
     * more — the milestone tasks ARE it, so the two can no longer disagree.
     *
     * <p>The columns stay because everything downstream reads them
     * ({@code ComparisonReadRepository.designatedPair} and friends, the cohort
     * header chip); only the way they are filled changed. The board save is
     * their ONLY writer — which is what makes them incapable of disagreeing
     * with the curriculum they came from.
     */
    private void syncMilestonePair(UUID cohortId) {
        UUID baseline = milestonePipeline(cohortId, MilestoneRole.BASELINE);
        UUID distance = milestonePipeline(cohortId, MilestoneRole.DISTANCE);
        ProgramSettings s = settings.findById(cohortId).orElse(null);
        // No row and no milestones: absence already reads as "no pair" (see
        // ProgramMapper.toDto), so don't create a row of defaults to say it twice.
        if (s == null && baseline == null && distance == null) {
            return;
        }
        if (s == null) {
            s = new ProgramSettings();
            s.setCohortId(cohortId);
        }
        s.setBaselinePipelineId(baseline);
        s.setDistancePipelineId(distance);
        settings.save(s);
    }

    /**
     * The pipeline behind the cohort's milestone task of this role, or null
     * when there is none (an undesignated side — no report is coming). Any
     * publish status counts, matching the comparison's own milestone lookup: a
     * draft milestone still names the instrument, and a milestone unpublished
     * after members answered it must still resolve.
     */
    private UUID milestonePipeline(UUID cohortId, MilestoneRole role) {
        return tasks.findByCohortAndMilestoneRole(cohortId, role).stream()
                .map(ProgramTask::getRefId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The per-task half of the spine rules — structure
     * ({@link ProgramRules#taskTypeFieldErrors}), the referenced object's
     * existence, and a milestone's frozen instrument. The milestone PAIR rules
     * are not here: they are cohort-level and read across the whole submitted
     * board, which is a different question from "is this task well formed".
     *
     * @param existing the task as stored, or {@code null} when the save creates it
     */
    private Map<String, String> taskSpineErrors(ProgramTask existing, ProgramTaskType type,
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
        if (errors.isEmpty() && existing != null
                && existing.getTaskType() == ProgramTaskType.ASSESSMENT
                && existing.getRefId() != null && !existing.getRefId().equals(refId)
                && spine.hasTaggedSubmissions(existing.getId())) {
            errors.put("refId", "Members have already answered this milestone — "
                    + "its assessment pipeline can no longer change.");
        }
        return errors;
    }

    // ------------------------------------------------------------------ pulse

    /**
     * Who is falling behind. {@code orgId} scopes the rows to that org's own
     * members (spec §13.7 — the org console's participation view); {@code null}
     * is the whole cross-org roster.
     */
    @Transactional(readOnly = true)
    public PulseResponse getPulse(UUID cohortId, UUID orgId) {
        List<CohortMemberRow> founders = cohortFounders(cohortId, orgId);
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<PulseColumn> columns = new ArrayList<>();
        List<UUID> taskIds = new ArrayList<>();
        List<ProgramModule> columnModules = new ArrayList<>();
        List<ProgramTask> columnTasks = new ArrayList<>();
        for (int mi = 0; mi < mods.size(); mi++) {
            List<ProgramTask> live = ProgramRules.liveTasks(mods.get(mi));
            for (int ti = 0; ti < live.size(); ti++) {
                ProgramTask task = live.get(ti);
                columns.add(new PulseColumn(task.getId(), mi + 1, ti + 1,
                        mods.get(mi).getName(), task.getName(), task.getDueDate()));
                taskIds.add(task.getId());
                columnModules.add(mods.get(mi));
                columnTasks.add(task);
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
        // Spec §3/§13: a COURSE the member's org can no longer open gates nothing
        // and counts nowhere — the same rule the matrix applies. One batched read
        // per distinct org on a cross-org roster.
        Map<UUID, Set<UUID>> blockedByOrg = founders.stream()
                .map(CohortMemberRow::getOrgId).distinct()
                .collect(Collectors.toMap(Function.identity(),
                        org -> myProgramService.blockedCourseIds(org, mods)));

        List<PulseRow> rows = founders.stream().map(member -> {
            Map<UUID, ProgramSubmission> mine = byUserThenTask.getOrDefault(member.getId(), Map.of());
            Map<UUID, JourneyTaskState> myTyped = typedByUser.getOrDefault(member.getId(), Map.of());
            Set<UUID> blockedCourses = blockedByOrg.getOrDefault(member.getOrgId(), Set.of());
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
                // side of the completion percentage — including a COURSE the
                // member's org can no longer open (ProgramRules.gates), exactly as
                // the matrix excludes it. completableInApp() alone counted a blocked
                // course as forever-incomplete and diverged from the matrix.
                if (ProgramRules.gates(columnTasks.get(i), blockedCourses)) {
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
     * The progress matrix over the enrolled founders (spec §2.3). Works for a
     * cohort in any lifecycle state — admins may inspect drafts and archives.
     * {@code orgId} scopes the rows to that org's own members (spec §13.7);
     * {@code null} is the whole cross-org roster.
     *
     * <p>Row-end readiness (FRI, delta, the pillar flag) reads ONLY sittings on
     * the cohort's own instruments ({@code CohortInstruments}), and the IDLE
     * flag measures silence from GREATEST(last activity, {@code launched_at})
     * — a cohort launched today must not open with its roster flagged idle.
     */
    @Transactional(readOnly = true)
    public CohortMatrixResponse getMatrix(UUID cohortId, UUID orgId) {
        List<ProgramModule> mods = modules.findByCohortIdOrderByPositionAsc(cohortId);
        List<CohortMemberRow> founders = cohortFounders(cohortId, orgId);
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
                .triage(cohortId, founderIds).stream()
                .collect(Collectors.toMap(CohortBoardReadRepository.TriageRow::userId,
                        Function.identity()));
        int pillarThreshold = boardReads.platformInt(KEY_PILLAR_THRESHOLD, DEFAULT_PILLAR_THRESHOLD);

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.OffsetDateTime idleCutoff = java.time.OffsetDateTime.now().minusDays(IDLE_DAYS);
        // Idle counts silence SINCE LAUNCH: a cohort launched today must not
        // flag every pre-existing member for the weeks before it existed.
        java.time.OffsetDateTime launchedAt = cohortService.require(cohortId).getLaunchedAt();
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
            // "—", which is the honest signal). The verdict measures from
            // GREATEST(last activity, cohort launch): idle means silent since
            // LAUNCH, never silence predating the cohort.
            if (tri != null && tri.lastActivityAt() != null) {
                java.time.OffsetDateTime idleSince =
                        launchedAt != null && launchedAt.isAfter(tri.lastActivityAt())
                                ? launchedAt : tri.lastActivityAt();
                if (idleSince.isBefore(idleCutoff)) {
                    flags.add(AttentionFlag.IDLE);
                }
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

            // Cohort-instrument sittings only (triage) — delta vs the previous
            // sitting on those instruments, null until there are two.
            java.math.BigDecimal friLatest = tri == null ? null : tri.friLatest();
            java.math.BigDecimal friDelta = tri == null || tri.friPrevious() == null
                    || tri.friLatest() == null ? null
                    : tri.friLatest().subtract(tri.friPrevious());
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
        return cohortFounders(cohortId, null);
    }

    /** {@link #cohortFounders(UUID)} cut to one org's own members when {@code orgId} is set. */
    private List<CohortMemberRow> cohortFounders(UUID cohortId, UUID orgId) {
        if (orgId == null) {
            cohortService.require(cohortId);
            return cohorts.findRoster(cohortId);
        }
        cohortService.requireAssigned(orgId, cohortId);
        return cohorts.findRoster(cohortId).stream()
                .filter(m -> orgId.equals(m.getOrgId()))
                .toList();
    }

    private int reached(ProgramModule m, List<CohortMemberRow> members) {
        return (int) members.stream()
                .filter(member -> ProgramRules.includes(m, member.getId()))
                .count();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
