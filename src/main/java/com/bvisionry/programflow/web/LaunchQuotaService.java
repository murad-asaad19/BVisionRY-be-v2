package com.bvisionry.programflow.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.SubscriptionTier.LaunchQuota;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.LaunchQuotaExceededException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.programflow.domain.CohortLaunchGrant;
import com.bvisionry.programflow.domain.CohortLaunchLedgerEntry;
import com.bvisionry.programflow.dto.CohortPlanUsageResponse;
import com.bvisionry.programflow.dto.GrantLaunchRequest;
import com.bvisionry.programflow.dto.LaunchLedgerResponse;
import com.bvisionry.programflow.dto.OnboardingChecklistResponse;
import com.bvisionry.programflow.repository.CohortBoardReadRepository;
import com.bvisionry.programflow.repository.CohortLaunchGrantRepository;
import com.bvisionry.programflow.repository.CohortLaunchLedgerRepository;

import lombok.RequiredArgsConstructor;

/**
 * The cohort-launch quota (redesign spec §8): calendar periods per tier, an
 * append-only ledger, SUPER_ADMIN grants, and the plan-usage/ledger reads.
 * Everything meters the BILLING ROOT org — sub-org cohorts consume the root
 * family's quota, so a customer can't reset the meter with a new sub-org.
 *
 * <p>Calendar periods are UTC — "resets Oct 1" means the same instant for
 * every user of an org. ponytail: no org-local timezone; add one on the org
 * row if a customer ever notices.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LaunchQuotaService {

    /** Same key + default as {@code AttentionThresholdsService} (organization slice). */
    static final String KEY_ONBOARDING_STALLED_HOURS = "attention.onboarding_stalled_hours";
    static final int DEFAULT_ONBOARDING_STALLED_HOURS = 24;

    static final String ACTION_LAUNCH_GRANTED = "COHORT_LAUNCH_GRANTED";
    static final String ENTITY_ORGANIZATION = "Organization";

    private final CohortLaunchLedgerRepository ledger;
    private final CohortLaunchGrantRepository grants;
    private final CohortBoardReadRepository boardReads;
    private final CurrentUserAccessor currentUser;
    private final AuditLogger audit;

    /* ------------------------------------------------------------- consume */

    /**
     * The quota half of a launch: MUST run inside the launch transaction
     * (spec §8 — check + ledger insert atomically). The paying unit is the
     * BILLING FAMILY, so the assigned orgs are collapsed to their DISTINCT
     * billing roots BEFORE anything is consumed — a cohort assigned to two
     * sub-orgs of one family is one participation and one charge, not a
     * double-charge that exhausts the shared meter against itself. Roots are
     * charged in sorted order so two multi-family launches can never take
     * their row locks in opposite order and deadlock.
     *
     * <p>The ceiling binds against each TARGET FAMILY's plan tier regardless of
     * WHO clicks Launch — cohorts are launched by SUPER_ADMIN on an org's
     * behalf, and §8 is "each assigned org pays". There is deliberately no
     * silent operator bypass: the escape hatch is the audited
     * {@link #grantExtraLaunch}, which raises the allowance ({@code verdict}:
     * perPeriod + grantsInPeriod) so the next launch clears — a logged grant,
     * not an invisible override. Unlimited tiers ({@code remaining == null})
     * are never blocked.
     */
    void consumeForLaunch(Collection<UUID> assignedOrgIds, UUID cohortId) {
        // Skip-if-charged, not unconditional: a RELAUNCH after unlaunch finds
        // the family's ledger row from the first launch and pays nothing —
        // "each family pays once per cohort" (spec §8), not once per click.
        assignedOrgIds.stream()
                .map(this::requireRoot)
                .distinct()
                .sorted()
                .forEach(root -> chargeRootUnlessCharged(root, cohortId));
    }

    /**
     * The assignment-time charge ({@code CohortService.assignOrg} on a
     * LAUNCHED cohort): the org's family pays UNLESS it already paid for this
     * cohort — adding a second sub-org of a family that launched with a
     * sibling is joining a participation the family already bought, not a new
     * one. The exists-check runs under the billing-root lock, so two racing
     * assignments of siblings serialize and the second sees the first's row.
     */
    void consumeUnlessCharged(UUID orgId, UUID cohortId) {
        chargeRootUnlessCharged(requireRoot(orgId), cohortId);
    }

    /** Lock the root, then charge only if this family never paid for this cohort. */
    private void chargeRootUnlessCharged(UUID root, UUID cohortId) {
        ledger.lockBillingRoot(root);
        // ponytail: linear scan of the family's ledger (small — quota-bounded);
        // switch to an exists query if a family's history ever gets long.
        boolean charged = ledger.ledgerRows(root).stream()
                .anyMatch(r -> cohortId.equals(r.getCohortId()));
        if (!charged) {
            chargeRootLocked(root, cohortId);
        }
    }

    /** The check + append, on an already-locked root. */
    private void chargeRootLocked(UUID root, UUID cohortId) {
        Verdict v = verdict(root);
        if (v.remaining() != null && v.remaining() <= 0) {
            throw new LaunchQuotaExceededException(refusal(v), v.nextAvailableDate(), v.tier());
        }
        CohortLaunchLedgerEntry entry = new CohortLaunchLedgerEntry();
        entry.setOrgId(root);
        entry.setCohortId(cohortId);
        entry.setLaunchedBy(currentUser.require().userId());
        try {
            // Flushed HERE so V181's UNIQUE (org_id, cohort_id) surfaces as a
            // clean 409 in this method, not a 500 at commit. The row locks
            // above make the constraint unreachable through today's paths —
            // it is the database's backstop for any path that forgets them.
            ledger.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalOperationException(
                    "This cohort has already been launched for this organization.");
        }
    }

    private static String refusal(Verdict v) {
        if (v.trial()) {
            return "Trial organizations can launch 1 cohort in total. Request an upgrade to keep launching.";
        }
        LaunchQuota quota = v.tier().launchQuota();
        if (quota != null && quota.perPeriod() == 0) {
            return "The " + v.tier().label() + " plan does not include cohort launches. "
                    + "Drafting is free — request an upgrade to launch.";
        }
        return "The " + v.tier().label() + " plan allows " + quota.describe()
                + " (sub-organizations share the parent organization's plan). "
                + "The next launch is available on " + v.nextAvailableDate()
                + " — or request an upgrade to launch sooner.";
    }

    /* --------------------------------------------------------------- reads */

    @Transactional(readOnly = true)
    public CohortPlanUsageResponse usage(UUID orgId) {
        UUID root = requireRoot(orgId);
        Verdict v = verdict(root);
        int stalledHours = boardReads.platformInt(
                KEY_ONBOARDING_STALLED_HOURS, DEFAULT_ONBOARDING_STALLED_HOURS);
        boolean stalled = v.orgCreatedAt() != null
                && v.orgCreatedAt().isBefore(Instant.now().minus(stalledHours, ChronoUnit.HOURS))
                && ledger.countByOrgId(root) == 0;
        return new CohortPlanUsageResponse(v.tier(), v.trial(), v.periodLabel(), v.allowance(),
                v.used(), v.remaining(), v.grantsThisPeriod(), v.nextResetDate(),
                v.nextAvailableDate(), stalled, v.orgCreatedAt());
    }

    @Transactional(readOnly = true)
    public LaunchLedgerResponse history(UUID orgId) {
        UUID root = requireRoot(orgId);
        return new LaunchLedgerResponse(
                ledger.ledgerRows(root).stream()
                        .map(r -> new LaunchLedgerResponse.LaunchRow(r.getId(), r.getCohortId(),
                                r.getCohortName(), r.getLaunchedAt(), r.getLaunchedByName()))
                        .toList(),
                grants.grantRows(root).stream()
                        .map(r -> new LaunchLedgerResponse.GrantRow(r.getId(), r.getCreatedAt(),
                                r.getGrantedByName(), r.getNote()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public OnboardingChecklistResponse checklist(UUID orgId) {
        var row = boardReads.checklist(orgId);
        return new OnboardingChecklistResponse(row.invitedMembers(),
                row.assignedFirstAssessment(), row.launchedCohort(), row.assignedCoach());
    }

    /* --------------------------------------------------------------- grant */

    /** SUPER_ADMIN only (guarded at the controller); §7b audit-logged. */
    public CohortPlanUsageResponse grantExtraLaunch(UUID orgId, GrantLaunchRequest req) {
        UUID root = requireRoot(orgId);
        UUID actorId = currentUser.require().userId();
        CohortLaunchGrant grant = new CohortLaunchGrant();
        grant.setOrgId(root);
        grant.setGrantedBy(actorId);
        grant.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        grants.save(grant);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestedForOrgId", orgId);
        if (grant.getNote() != null) {
            details.put("note", grant.getNote());
        }
        audit.log(actorId, root, ACTION_LAUNCH_GRANTED, ENTITY_ORGANIZATION, root, details);
        return usage(orgId);
    }

    /* ------------------------------------------------------------- verdict */

    /**
     * The one quota computation (consume, usage card and grant response all
     * read it): tier allowance for the current UTC calendar period + grants
     * in the period − ledger entries in the period; an active trial overrides
     * to 1 launch TOTAL (lifetime, grants included) regardless of tier.
     */
    private record Verdict(SubscriptionTier tier, boolean trial, String periodLabel,
                           Integer allowance, long used, Integer remaining, int grantsThisPeriod,
                           LocalDate nextResetDate, LocalDate nextAvailableDate,
                           Instant orgCreatedAt) {}

    private Verdict verdict(UUID rootOrgId) {
        var row = ledger.rootBillingRow(rootOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", rootOrgId.toString()));
        SubscriptionTier tier = SubscriptionTier.valueOf(row.getTier());
        boolean trial = tier.isPaid() && row.getTrialEndsAt() != null
                && row.getTrialEndsAt().isAfter(Instant.now());

        if (trial) {
            int grantsAllTime = (int) grants.countByOrgId(rootOrgId);
            int allowance = 1 + grantsAllTime;
            long used = ledger.countByOrgId(rootOrgId);
            return new Verdict(tier, true, "trial", allowance, used,
                    (int) Math.max(0, allowance - used), grantsAllTime,
                    null, null, row.getCreatedAt());
        }
        LaunchQuota quota = tier.launchQuota();
        if (quota == null) {
            return new Verdict(tier, false, "unlimited", null, ledger.countByOrgId(rootOrgId),
                    null, 0, null, null, row.getCreatedAt());
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime periodStart = quota.unit().periodStart(today)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        int grantsInPeriod = (int) grants
                .countByOrgIdAndCreatedAtGreaterThanEqual(rootOrgId, periodStart);
        long used = ledger.countByOrgIdAndLaunchedAtGreaterThanEqual(rootOrgId, periodStart);
        int allowance = quota.perPeriod() + grantsInPeriod;
        int remaining = (int) Math.max(0, allowance - used);
        LocalDate nextReset = quota.unit().nextPeriodStart(today);
        LocalDate nextAvailable = remaining > 0 ? null
                : quota.perPeriod() > 0 ? nextReset : null;
        return new Verdict(tier, false, quota.unit().label(), allowance, used, remaining,
                grantsInPeriod, nextReset, nextAvailable, row.getCreatedAt());
    }

    private UUID requireRoot(UUID orgId) {
        UUID root = ledger.billingRootOf(orgId);
        if (root == null) {
            throw new ResourceNotFoundException("Organization", orgId.toString());
        }
        return root;
    }
}
