package com.bvisionry.programflow.web;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.programflow.repository.BoardRestoreRepository;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortOrgAssignment;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.ProgramSurface;
import com.bvisionry.programflow.dto.AssignOrgRequest;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CohortOrgDto;
import com.bvisionry.programflow.dto.CohortRosterEntryDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.ProgramOrgDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.programflow.dto.UpdateOrgAssignmentRequest;
import com.bvisionry.programflow.repository.CohortOrgAssignmentRepository;
import com.bvisionry.programflow.repository.CohortOrgNameRow;
import com.bvisionry.programflow.repository.CohortProgressRow;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.OrgMemberRow;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

/**
 * Cohort management in the platform-cohort model (spec §13): cohorts are
 * authored by super admins and ASSIGNED to organizations, each assignment
 * carrying the enrollment rule (all members now / selected members /
 * auto-enroll future joiners). Lifecycle is the two-way toggle
 * DRAFT ⇄ LAUNCHED (spec §8, two-state since V183).
 *
 * <p>Quota: each assigned BILLING FAMILY pays for a launched cohort from its
 * root plan, once — {@link #launch} consumes one launch per distinct billing
 * root across the assignments, {@link #assignOrg} consumes at assignment time
 * when the cohort is already launched and the family has not paid for it yet.
 * Both go through {@link LaunchQuotaService} in the same transaction as the
 * append-only ledger insert.
 *
 * <p>Org admins keep exactly one write: {@link #setOrgMembers} over their OWN
 * members (spec §13.8) — everything else is super-admin only at the
 * controller.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CohortService {

    static final String ENTITY_COHORT = "Cohort";
    static final String ACTION_LAUNCHED = "COHORT_LAUNCHED";
    static final String ACTION_UNLAUNCHED = "COHORT_UNLAUNCHED";
    static final String ACTION_ORG_ASSIGNED = "COHORT_ORG_ASSIGNED";
    static final String ACTION_ORG_UNASSIGNED = "COHORT_ORG_UNASSIGNED";

    /**
     * Enrollment is learners-only: {@link #orgMemberIds} is
     * {@code Learner.ROLE_IN AND status = 'ACTIVE'} — MEMBER, ORG_ADMIN and
     * SUPER_ADMIN (an org-scoped admin takes the program like a founder does,
     * matching the assessment slice's targetRoles), while COACH and
     * INSTRUCTOR still fail it even though they DO belong to the org: they
     * staff the program. The old "do not belong to this organization" wording
     * sent admins hunting for the wrong problem.
     */
    static final String NOT_ENROLLABLE =
            "One or more of the selected people are not active learners in this organization";

    private final CohortRepository cohorts;
    private final BoardRestoreRepository boardRestore;
    private final CohortOrgAssignmentRepository assignments;
    private final ApplicationEventPublisher events;
    private final LaunchQuotaService launchQuota;
    private final AuditLogger audit;
    private final CurrentUserAccessor currentUser;
    private final EntityManager entityManager;

    /**
     * Every platform cohort, board order (super-admin authoring console), each
     * labelled with the orgs it is assigned to — two cohorts may share a name
     * since §13, and the switcher is the only place to tell them apart.
     */
    @Transactional(readOnly = true)
    public List<CohortDto> listAll() {
        Map<UUID, List<String>> orgNames = cohorts.findAllOrgNames().stream()
                .collect(Collectors.groupingBy(CohortOrgNameRow::getCohortId,
                        Collectors.mapping(CohortOrgNameRow::getOrgName, Collectors.toList())));
        Map<UUID, Long> workCounts = boardRestore.memberWorkCountByCohort();
        return cohorts.findAllByOrderByPositionAsc().stream()
                .map(c -> CohortDto.of(c, orgNames.getOrDefault(c.getId(), List.of()),
                        workCounts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /**
     * The cohorts assigned to an org — the participation view. Rosters are
     * cut down to the org's OWN members (spec §13.7: an org admin never sees
     * another org's people, not even as opaque ids).
     */
    @Transactional(readOnly = true)
    public List<CohortDto> listAssigned(UUID orgId) {
        Set<UUID> mine = orgMemberIds(orgId);
        Map<UUID, CohortProgressRow> progress = cohorts.findAssignedProgressStats(orgId).stream()
                .collect(Collectors.toMap(CohortProgressRow::getCohortId, r -> r));
        return cohorts.findAssigned(orgId).stream()
                .map(c -> {
                    CohortProgressRow p = progress.get(c.getId());
                    return orgScoped(c, mine, p == null ? 0 : p.getModuleCount(),
                            p == null ? "Week" : p.getStageLabel());
                })
                .toList();
    }

    /**
     * The cohort's roster with names and orgs — what the builder needs to pick
     * a module audience (spec §13). Names only; progress lives on the org
     * console (§13.7).
     */
    @Transactional(readOnly = true)
    public List<CohortRosterEntryDto> roster(UUID cohortId) {
        require(cohortId);
        return cohorts.findRoster(cohortId).stream()
                .map(r -> new CohortRosterEntryDto(r.getId(), r.getName(), r.getEmail(),
                        r.getOrgId(), r.getOrgName()))
                .toList();
    }

    /** Every sub-org with its console membership and learner / cohort / workshop counts. */
    @Transactional(readOnly = true)
    public List<ProgramOrgDto> listOrgs() {
        return cohorts.findOrgProgramRows().stream()
                .map(r -> new ProgramOrgDto(r.getId(), r.getName(), r.getDescription(),
                        r.getParentName(), (int) r.getMemberCount(), (int) r.getCohortCount(),
                        (int) r.getWorkshopCount(), r.getInProgramFlow(), r.getInWorkshops()))
                .toList();
    }

    /** Lists the org on a console (idempotent); its existing data is reused as-is. */
    public void addToSurface(UUID orgId, ProgramSurface surface) {
        cohorts.addToSurface(orgId, surface.name());
    }

    /**
     * Takes the org off a console. Deliberately non-destructive: cohorts,
     * workshops and every learner record survive, so re-adding the org restores
     * it exactly. Deleting that data is a separate, explicit choice.
     */
    public void removeFromSurface(UUID orgId, ProgramSurface surface) {
        cohorts.removeFromSurface(orgId, surface.name());
    }

    /** Creates a DRAFT — free; quota is only ever consumed per assigned org at launch. */
    public CohortDto create(CreateCohortRequest req) {
        Cohort c = new Cohort();
        c.setName(req.name());
        c.setPosition(cohorts.findAllByOrderByPositionAsc().size());
        return CohortDto.of(cohorts.save(c));
    }

    /** Rename only — lifecycle moves through {@link #launch}/{@link #unlaunch}. */
    public CohortDto update(UUID cohortId, UpdateCohortRequest req) {
        Cohort c = require(cohortId);
        c.setName(req.name());
        return CohortDto.of(c);
    }

    /* ------------------------------------------------------------ lifecycle */

    /**
     * DRAFT → LAUNCHED. Every assigned billing family pays ONCE PER COHORT:
     * the assignments are collapsed to their distinct billing roots and one
     * launch is consumed per root that has not already paid for this cohort —
     * so relaunching after {@link #unlaunch} is free for families the ledger
     * already carries. Check + append-only ledger insert in THIS transaction,
     * serialized on the billing-root row lock (spec §8). Any family over
     * quota → 409 and the whole launch rolls back — the admin unassigns that
     * family's orgs or grants it a launch, then retries.
     */
    public CohortDto launch(UUID cohortId) {
        Cohort c = requireLocked(cohortId);
        if (c.getStatus() != CohortStatus.DRAFT) {
            throw new IllegalOperationException("Only a draft cohort can be launched — this one is "
                    + c.getStatus().name().toLowerCase() + ".");
        }
        launchQuota.consumeForLaunch(
                assignments.findByCohortId(cohortId).stream()
                        .map(CohortOrgAssignment::getOrgId)
                        .toList(),
                cohortId);
        boolean firstLaunch = c.getLaunchedAt() == null;
        c.setStatus(CohortStatus.LAUNCHED);
        if (firstLaunch) {
            // launchedAt is "when this cohort FIRST went live" — the floor for
            // milestone adoption (TaskSpineRepository.ADOPTABLE_SITTINGS), so a
            // relaunch keeps the original stamp.
            c.setLaunchedAt(OffsetDateTime.now());
        }
        auditLifecycle(null, c, ACTION_LAUNCHED);
        // The moment the cohort FIRST becomes visible is the moment the roster
        // hears about it — draft-time enrolment stays silent by design, and a
        // relaunch does not re-blast people who already heard.
        // ponytail: relaunch is silent; members enrolled during a DRAFT rework
        // window aren't notified — add cohort_members.enrolled_at and notify
        // enrolled_at > last unlaunch if that gap bites.
        if (firstLaunch && !c.getMemberIds().isEmpty()) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(
                    c.getName(), List.copyOf(c.getMemberIds())));
        }
        return CohortDto.of(c);
    }

    /**
     * LAUNCHED → DRAFT: pulls the cohort back to the drawing board — invisible
     * to members again, no notifications, no survey submits. NEVER refunds
     * quota (the ledger is append-only, spec §8); relaunching later re-charges
     * only families that have not paid for this cohort yet. {@code launchedAt}
     * survives: it records the FIRST launch, so milestone adoptions keyed on it
     * outlive an unlaunch/relaunch cycle.
     */
    public CohortDto unlaunch(UUID cohortId) {
        Cohort c = requireLocked(cohortId);
        if (c.getStatus() != CohortStatus.LAUNCHED) {
            throw new IllegalOperationException("Only a launched cohort can be unlaunched — this one is "
                    + c.getStatus().name().toLowerCase() + ".");
        }
        c.setStatus(CohortStatus.DRAFT);
        auditLifecycle(null, c, ACTION_UNLAUNCHED);
        return CohortDto.of(c);
    }

    /**
     * Deleting is only possible while no member has worked in the cohort —
     * the cascade would otherwise take every submission, attendance record
     * and growth summary with it. The launch ledger stands regardless (no
     * refund, spec §8).
     */
    public void delete(UUID cohortId) {
        Cohort c = require(cohortId);
        long members = boardRestore.memberWorkCount(cohortId);
        if (members > 0) {
            throw new IllegalOperationException(
                    "This cohort has work from " + members + " member" + (members == 1 ? "" : "s")
                            + " and cannot be deleted.");
        }
        cohorts.delete(c);
    }

    /* ------------------------------------------------------ org assignment */

    /** The cohort's assigned orgs with their enrollment rule and headcount. */
    @Transactional(readOnly = true)
    public List<CohortOrgDto> listOrgAssignments(UUID cohortId) {
        require(cohortId);
        return cohorts.findAssignmentRows(cohortId).stream()
                .map(r -> new CohortOrgDto(r.getOrgId(), r.getOrgName(), r.getParentName(),
                        r.getAutoEnroll(), r.getAssignedAt(), (int) r.getEnrolledCount()))
                .toList();
    }

    /**
     * Assigns an org to the cohort with its enrollment rule. On a LAUNCHED
     * cohort the org's billing family pays its launch quota right here —
     * participation in a running program is the metered act (spec §13.4) —
     * UNLESS the family already paid for this cohort through a sibling
     * sub-org: joining an already-bought participation is free.
     */
    public CohortDto assignOrg(UUID cohortId, AssignOrgRequest req) {
        Cohort c = require(cohortId);
        if (!cohorts.orgExists(req.orgId())) {
            throw new ResourceNotFoundException("Organization", req.orgId().toString());
        }
        if (assignments.existsByCohortIdAndOrgId(cohortId, req.orgId())) {
            throw new IllegalOperationException("This organization is already assigned to the cohort.");
        }
        if (c.getStatus() == CohortStatus.LAUNCHED) {
            launchQuota.consumeUnlessCharged(req.orgId(), cohortId);
        }
        CohortOrgAssignment a = new CohortOrgAssignment();
        a.setCohortId(cohortId);
        a.setOrgId(req.orgId());
        a.setAutoEnroll(req.autoEnroll());
        a.setAssignedBy(currentUser.require().userId());
        assignments.save(a);

        List<UUID> enrolled = enrollForAssignment(c, req);
        audit.log(currentUser.require().userId(), req.orgId(), ACTION_ORG_ASSIGNED,
                ENTITY_COHORT, c.getId(), Map.of("name", c.getName()));
        notifyEnrolled(c, enrolled);
        return CohortDto.of(c);
    }

    /** Changes the assignment's auto-enroll rule (nothing retroactive). */
    public CohortDto updateOrgAssignment(UUID cohortId, UUID orgId, UpdateOrgAssignmentRequest req) {
        Cohort c = require(cohortId);
        CohortOrgAssignment a = assignments.findByCohortIdAndOrgId(cohortId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", orgId.toString()));
        a.setAutoEnroll(req.autoEnroll());
        return CohortDto.of(c);
    }

    /**
     * Removes the org from the cohort: assignment row plus the org's own
     * members from the roster. Their submissions stay (history is never
     * destroyed here); quota is never refunded (spec §8).
     */
    public CohortDto unassignOrg(UUID cohortId, UUID orgId) {
        Cohort c = require(cohortId);
        CohortOrgAssignment a = assignments.findByCohortIdAndOrgId(cohortId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", orgId.toString()));
        assignments.delete(a);
        c.getMemberIds().removeAll(orgMemberIds(orgId));
        audit.log(currentUser.require().userId(), orgId, ACTION_ORG_UNASSIGNED,
                ENTITY_COHORT, c.getId(), Map.of("name", c.getName()));
        return CohortDto.of(c);
    }

    /**
     * Auto-enroll hook: a member just joined (or moved into) {@code orgId} —
     * enroll them in every cohort whose assignment to that org says so.
     * DRAFT cohorts enroll silently (the roster hears at launch).
     *
     * <p>Learners-only, the same rule {@link #setOrgMembers} and
     * {@link #enrollForAssignment} enforce ({@link #NOT_ENROLLABLE}). The check
     * lives HERE, on the one entry point both auto-enroll listeners call, and
     * tests the one id via {@link CohortRepository#isActiveOrgMember} — the
     * single-id form of the same active-learner predicate — rather than
     * materialising the whole org roster to check one member. It deliberately
     * does NOT filter the event's
     * {@code userType}: that is the member-type code (FOUNDER / LEADER / …),
     * not the role, so a COACH invited to staff the program looks exactly like
     * a founder there. Skipping silently rather than throwing is the right
     * shape for an AFTER_COMMIT listener — nobody asked for this enrollment,
     * so there is nobody to hand a 400 to. And the row it prevents is not
     * cosmetic: {@code findRoster} is role-filtered, so a staffing coach in
     * {@code memberIds} would make the raw count disagree with every
     * roster-derived surface forever, and {@link #setOrgMembers}'s
     * {@code removeAll(mine)} — mine being learners only — could never clear it.
     */
    public void autoEnroll(UUID orgId, UUID userId) {
        if (!cohorts.isActiveOrgMember(orgId, userId)) {
            return;
        }
        for (CohortOrgAssignment a : assignments.findByOrgId(orgId)) {
            if (!a.isAutoEnroll()) {
                continue;
            }
            Cohort c = cohorts.findById(a.getCohortId()).orElse(null);
            if (c == null) {
                continue;
            }
            if (c.getMemberIds().add(userId)) {
                notifyEnrolled(c, List.of(userId));
            }
        }
    }

    /* --------------------------------------------------------------- roster */

    /**
     * Replaces ONE org's slice of the roster (spec §13.8 — an org admin
     * manages their own members only; other orgs' enrollments are untouched).
     */
    public CohortDto setOrgMembers(UUID orgId, UUID cohortId, UpdateCohortMembersRequest req) {
        Cohort c = requireAssigned(orgId, cohortId);
        Set<UUID> mine = orgMemberIds(orgId);
        if (!mine.containsAll(req.memberIds())) {
            throw new BadRequestException(NOT_ENROLLABLE);
        }
        List<UUID> added = req.memberIds().stream()
                .filter(id -> !c.getMemberIds().contains(id))
                .toList();
        Set<UUID> roster = new LinkedHashSet<>(c.getMemberIds());
        roster.removeAll(mine);
        roster.addAll(req.memberIds());
        c.setMemberIds(roster);
        notifyEnrolled(c, added);
        return orgScoped(c, mine);
    }

    /* -------------------------------------------------------------- helpers */

    private List<UUID> enrollForAssignment(Cohort c, AssignOrgRequest req) {
        Set<UUID> mine = orgMemberIds(req.orgId());
        List<UUID> toEnroll;
        if (req.enrollAllMembers()) {
            toEnroll = List.copyOf(mine);
        } else {
            if (!mine.containsAll(req.memberIds())) {
                throw new BadRequestException(NOT_ENROLLABLE);
            }
            toEnroll = req.memberIds();
        }
        List<UUID> added = toEnroll.stream().filter(id -> !c.getMemberIds().contains(id)).toList();
        c.getMemberIds().addAll(toEnroll);
        return added;
    }

    /**
     * Only a LAUNCHED cohort notifies newcomers — a member added to a DRAFT
     * can't see it yet (they hear at launch with everyone else).
     */
    private void notifyEnrolled(Cohort c, List<UUID> added) {
        if (!added.isEmpty() && c.getStatus() == CohortStatus.LAUNCHED) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(c.getName(), added));
        }
    }

    private Set<UUID> orgMemberIds(UUID orgId) {
        return cohorts.findOrgMembers(orgId).stream()
                .map(OrgMemberRow::getId).collect(Collectors.toSet());
    }

    /**
     * The cohort with its roster cut to the given org's members, and the
     * neutral 0/"Week" progress default ({@link CohortDto} explains why
     * that's safe here — {@link #setOrgMembers} is this overload's only
     * other caller and its response never reaches the cards' cache).
     */
    private static CohortDto orgScoped(Cohort c, Set<UUID> orgMemberIds) {
        return orgScoped(c, orgMemberIds, 0, "Week");
    }

    /** {@link #orgScoped(Cohort, Set)} with the real progress stats for the cohort cards. */
    private static CohortDto orgScoped(Cohort c, Set<UUID> orgMemberIds, int moduleCount, String stageLabel) {
        // orgNames stays empty here: the org console already knows whose page it is.
        return new CohortDto(c.getId(), c.getName(), c.getPosition(), c.getStatus(),
                c.getLaunchedAt(),
                c.getMemberIds().stream().filter(orgMemberIds::contains).toList(),
                List.of(), moduleCount, stageLabel, 0);
    }

    private void auditLifecycle(UUID orgId, Cohort c, String action) {
        audit.log(currentUser.require().userId(), orgId, action, ENTITY_COHORT, c.getId(),
                Map.of("name", c.getName()));
    }

    /* --------------------------------------------------------------- guards */

    /** The cohort, by id — platform artifacts have no org path to check (spec §13). */
    Cohort require(UUID cohortId) {
        return cohorts.findById(cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    /**
     * {@link #require} under the cohort's row lock. Lifecycle transitions are
     * read-then-decide, so the read takes {@code PESSIMISTIC_WRITE}: a
     * double-clicked Launch, or an unlaunch racing a launch, serializes here
     * and the loser re-reads the committed status into the ordinary 409
     * instead of charging the family again, re-notifying the roster or
     * lost-updating the state. (V181's UNIQUE on the ledger backstops the
     * charge at the database even for a path without this lock.)
     */
    private Cohort requireLocked(UUID cohortId) {
        Cohort c = entityManager.find(Cohort.class, cohortId, LockModeType.PESSIMISTIC_WRITE);
        if (c == null) {
            throw new ResourceNotFoundException("Cohort", cohortId.toString());
        }
        return c;
    }

    /** Tenant guard for the org participation surface: the cohort must be assigned to the org. */
    Cohort requireAssigned(UUID orgId, UUID cohortId) {
        Cohort c = require(cohortId);
        if (!assignments.existsByCohortIdAndOrgId(cohortId, orgId)) {
            throw new ResourceNotFoundException("Cohort", cohortId.toString());
        }
        return c;
    }
}
