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
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.domain.ProgramSurface;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.ProgramOrgDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin cohort management: list, create, rename, lifecycle
 * (DRAFT → LAUNCHED → COMPLETED / ARCHIVED, spec §8), delete, enrolment.
 *
 * <p>Creating a DRAFT is free — the old creation-time rolling-window ceiling
 * is gone (spec §8: "drafts are free"). The paid act is LAUNCH, which consumes
 * calendar-period quota via {@link LaunchQuotaService} in the same
 * transaction as the append-only ledger insert.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CohortService {

    static final String ENTITY_COHORT = "Cohort";
    static final String ACTION_LAUNCHED = "COHORT_LAUNCHED";
    static final String ACTION_COMPLETED = "COHORT_COMPLETED";
    static final String ACTION_ARCHIVED = "COHORT_ARCHIVED";

    private final CohortRepository cohorts;
    private final TeamRepository teams;
    private final ApplicationEventPublisher events;
    private final LaunchQuotaService launchQuota;
    private final AuditLogger audit;
    private final CurrentUserAccessor currentUser;

    @Transactional(readOnly = true)
    public List<CohortDto> list(UUID orgId) {
        return cohorts.findByOrgIdOrderByPositionAsc(orgId).stream().map(CohortDto::of).toList();
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

    /**
     * Creates a DRAFT — free on every tier; quota is only consumed by launch.
     * No enrolment notification here: a DRAFT is invisible to members, so the
     * roster is told at {@link #launch} instead.
     */
    public CohortDto create(UUID orgId, CreateCohortRequest req) {
        Cohort c = new Cohort();
        c.setOrgId(orgId);
        c.setName(req.name());
        c.setPosition(cohorts.findByOrgIdOrderByPositionAsc(orgId).size());
        if (req.enrollAllMembers()) {
            teams.findOrgMembers(orgId).forEach(m -> c.getMemberIds().add(m.getId()));
        }
        return CohortDto.of(cohorts.save(c));
    }

    /** Rename only — lifecycle moves through {@link #launch}/{@link #complete}/{@link #archive}. */
    public CohortDto update(UUID orgId, UUID cohortId, UpdateCohortRequest req) {
        Cohort c = requireEditable(orgId, cohortId);
        c.setName(req.name());
        return CohortDto.of(c);
    }

    /* ------------------------------------------------------------ lifecycle */

    /**
     * DRAFT → LAUNCHED. Consumes launch quota: the check and the append-only
     * ledger insert happen in THIS transaction, serialized on the billing-root
     * row lock (spec §8) — two concurrent launches can't both take the last
     * slot. Quota exhausted → 409 with {nextAvailableDate, tier}.
     */
    public CohortDto launch(UUID orgId, UUID cohortId) {
        Cohort c = require(orgId, cohortId);
        if (c.getStatus() != CohortStatus.DRAFT) {
            throw new IllegalOperationException("Only a draft cohort can be launched — this one is "
                    + c.getStatus().name().toLowerCase() + ".");
        }
        launchQuota.consume(orgId, cohortId);
        c.setStatus(CohortStatus.LAUNCHED);
        c.setLaunchedAt(OffsetDateTime.now());
        auditLifecycle(orgId, c, ACTION_LAUNCHED);
        // The moment the cohort becomes visible is the moment the roster hears
        // about it — draft-time enrolment stays silent by design.
        if (!c.getMemberIds().isEmpty()) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(
                    orgId, c.getName(), List.copyOf(c.getMemberIds())));
        }
        return CohortDto.of(c);
    }

    /** LAUNCHED → COMPLETED: read-only for members (the closing screen); never refunds quota. */
    public CohortDto complete(UUID orgId, UUID cohortId) {
        Cohort c = require(orgId, cohortId);
        if (c.getStatus() != CohortStatus.LAUNCHED) {
            throw new IllegalOperationException("Only a launched cohort can be completed — this one is "
                    + c.getStatus().name().toLowerCase() + ".");
        }
        c.setStatus(CohortStatus.COMPLETED);
        c.setCompletedAt(OffsetDateTime.now());
        auditLifecycle(orgId, c, ACTION_COMPLETED);
        return CohortDto.of(c);
    }

    /**
     * DRAFT / COMPLETED → ARCHIVED: read-only for everyone, invisible to
     * members. A LAUNCHED cohort must be completed first — archiving is
     * shelving, not an emergency stop.
     */
    public CohortDto archive(UUID orgId, UUID cohortId) {
        Cohort c = require(orgId, cohortId);
        if (c.getStatus() != CohortStatus.DRAFT && c.getStatus() != CohortStatus.COMPLETED) {
            throw new IllegalOperationException("Only a draft or completed cohort can be archived — "
                    + "this one is " + c.getStatus().name().toLowerCase() + ".");
        }
        c.setStatus(CohortStatus.ARCHIVED);
        c.setArchivedAt(OffsetDateTime.now());
        auditLifecycle(orgId, c, ACTION_ARCHIVED);
        return CohortDto.of(c);
    }

    private void auditLifecycle(UUID orgId, Cohort c, String action) {
        audit.log(currentUser.require().userId(), orgId, action, ENTITY_COHORT, c.getId(),
                Map.of("name", c.getName()));
    }

    /** Deleting is allowed in any state; the launch ledger stands (no refund, spec §8). */
    public void delete(UUID orgId, UUID cohortId) {
        cohorts.delete(require(orgId, cohortId));
    }

    /** Replaces the enrolled learner set, validating every id is an org member. */
    public CohortDto setMembers(UUID orgId, UUID cohortId, UpdateCohortMembersRequest req) {
        Cohort c = requireEditable(orgId, cohortId);
        Set<UUID> orgMemberIds = teams.findOrgMembers(orgId).stream()
                .map(OrgMemberRow::getId).collect(Collectors.toSet());
        if (!orgMemberIds.containsAll(req.memberIds())) {
            throw new BadRequestException("One or more learners do not belong to this organization");
        }
        List<UUID> added = req.memberIds().stream()
                .filter(id -> !c.getMemberIds().contains(id))
                .toList();
        c.setMemberIds(new LinkedHashSet<>(req.memberIds()));
        // Only a LAUNCHED cohort notifies newcomers — a member added to a
        // DRAFT can't see it yet (they hear at launch with everyone else),
        // and a COMPLETED one has nothing left to start on.
        if (!added.isEmpty() && c.getStatus() == CohortStatus.LAUNCHED) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(orgId, c.getName(), added));
        }
        return CohortDto.of(c);
    }

    /** The cohort, guarded to the org path (tenant isolation). */
    Cohort require(UUID orgId, UUID cohortId) {
        return cohorts.findById(cohortId)
                .filter(c -> c.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    /**
     * {@link #require} + the ARCHIVED read-only rule: members and curriculum
     * stay editable in any non-archived state; an archived cohort refuses
     * every mutation with a clear 409.
     */
    Cohort requireEditable(UUID orgId, UUID cohortId) {
        Cohort c = require(orgId, cohortId);
        if (c.getStatus() == CohortStatus.ARCHIVED) {
            throw new IllegalOperationException("This cohort is archived and read-only.");
        }
        return c;
    }
}
