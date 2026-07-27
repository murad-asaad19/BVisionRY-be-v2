package com.bvisionry.programflow.web;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.event.ProgramFlowEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.PremiumFeatureGuard;
import com.bvisionry.programflow.domain.Cohort;
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

/** Admin cohort management: list, create, rename/finish, delete, enrolment. */
@Service
@RequiredArgsConstructor
@Transactional
public class CohortService {

    private final CohortRepository cohorts;
    private final TeamRepository teams;
    private final ApplicationEventPublisher events;
    /** Shared-kernel entitlement gate — supplies the effective tier and the super-admin bypass. */
    private final PremiumFeatureGuard entitlements;

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

    public CohortDto create(UUID orgId, CreateCohortRequest req) {
        requireCohortAllowance(orgId);
        Cohort c = new Cohort();
        c.setOrgId(orgId);
        c.setName(req.name());
        c.setPosition(cohorts.findByOrgIdOrderByPositionAsc(orgId).size());
        if (req.enrollAllMembers()) {
            teams.findOrgMembers(orgId).forEach(m -> c.getMemberIds().add(m.getId()));
        }
        Cohort saved = cohorts.save(c);
        if (!saved.getMemberIds().isEmpty()) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(
                    orgId, saved.getName(), List.copyOf(saved.getMemberIds())));
        }
        return CohortDto.of(saved);
    }

    public CohortDto update(UUID orgId, UUID cohortId, UpdateCohortRequest req) {
        Cohort c = require(orgId, cohortId);
        c.setName(req.name());
        c.setStatus(req.status());
        return CohortDto.of(c);
    }

    public void delete(UUID orgId, UUID cohortId) {
        cohorts.delete(require(orgId, cohortId));
    }

    /** Replaces the enrolled learner set, validating every id is an org member. */
    public CohortDto setMembers(UUID orgId, UUID cohortId, UpdateCohortMembersRequest req) {
        Cohort c = require(orgId, cohortId);
        Set<UUID> orgMemberIds = teams.findOrgMembers(orgId).stream()
                .map(OrgMemberRow::getId).collect(Collectors.toSet());
        if (!orgMemberIds.containsAll(req.memberIds())) {
            throw new BadRequestException("One or more learners do not belong to this organization");
        }
        List<UUID> added = req.memberIds().stream()
                .filter(id -> !c.getMemberIds().contains(id))
                .toList();
        c.setMemberIds(new LinkedHashSet<>(req.memberIds()));
        if (!added.isEmpty()) {
            events.publishEvent(new ProgramFlowEvents.CohortEnrolled(orgId, c.getName(), added));
        }
        return CohortDto.of(c);
    }

    /**
     * Refuses a cohort the org's plan has no room for. The plan meters a RATE —
     * cohorts per quarter (Starter) or per month (Growth), unlimited on Founder
     * Success — never a founder headcount.
     *
     * <p>Enforcement is deliberately SOFT and one-sided: it runs on CREATE only.
     * Nothing here ever disables, hides or reclassifies a cohort that already
     * exists, so a downgrade (or this rule shipping at all) can never take an
     * accelerator's running programme away mid-cohort.
     *
     * <p>The window is ROLLING, not calendar. "1 cohort per quarter" is a rate,
     * and a calendar quarter lets an org create one on 31 Mar and another on
     * 1 Apr — two cohorts in two days on a plan sold as one per quarter. Rolling
     * is also the cheaper thing to explain in the refusal message: "in the past
     * quarter" needs no reference to which quarter we are in.
     *
     * <p>SUPER_ADMIN bypasses, exactly as it bypasses every other entitlement
     * gate ({@code PremiumFeatureGuard.checkPremium}) — an operator running a
     * demo or seeding a customer must not be blocked by the customer's plan, and
     * it is the escape hatch that keeps "soft" honest.
     */
    private void requireCohortAllowance(UUID orgId) {
        // Empty = super admin, i.e. no ceiling applies at all. Do NOT read it as FREE.
        SubscriptionTier tier = entitlements.governingTier(orgId).orElse(null);
        if (tier == null) return;

        SubscriptionTier.CohortRate rate = tier.cohortRate();
        if (rate == null) return; // Founder Success — unlimited.

        long used = cohorts.countCreatedInBillingFamilySince(
                orgId, OffsetDateTime.now().minus(rate.window()));
        if (used < rate.max()) return;

        throw new IllegalOperationException(
                "The " + tier.label() + " plan allows " + rate.describe()
                        + ". Your organization has already created " + used
                        + (used == 1 ? " cohort" : " cohorts") + " in the past " + rate.windowLabel()
                        + " — sub-organizations share the parent organization's plan. Upgrade the plan"
                        + " to run more cohorts; the cohorts you already have are unaffected.");
    }

    /** The cohort, guarded to the org path (tenant isolation). */
    Cohort require(UUID orgId, UUID cohortId) {
        return cohorts.findById(cohortId)
                .filter(c -> c.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }
}
