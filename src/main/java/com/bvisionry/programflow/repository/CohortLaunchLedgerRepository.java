package com.bvisionry.programflow.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.CohortLaunchLedgerEntry;

/**
 * The append-only launch ledger (spec §8) plus the two org-hierarchy reads the
 * quota check needs. The organization reads are native + soft-coupled by SQL
 * (the {@code CohortRepository.findOrgProgramRows} precedent): no Java edge on
 * the organization feature.
 */
public interface CohortLaunchLedgerRepository extends JpaRepository<CohortLaunchLedgerEntry, UUID> {

    /** Launches in the current calendar period (org = billing root). */
    long countByOrgIdAndLaunchedAtGreaterThanEqual(UUID orgId, OffsetDateTime since);

    /** All-time launches — the trial meter ("1 cohort total"). */
    long countByOrgId(UUID orgId);

    /**
     * §7b audit view, newest first, with display names joined in. LEFT joins:
     * the ledger row outlives both the cohort (no FK by design) and the
     * launching user (SET NULL) — those render as null names, never hide the
     * row.
     */
    @Query(value = """
            SELECT l.id AS id, l.cohort_id AS cohortId, c.name AS cohortName,
                   l.launched_at AS launchedAt, u.name AS launchedByName
            FROM cohort_launch_ledger l
            LEFT JOIN cohorts c ON c.id = l.cohort_id
            LEFT JOIN users u ON u.id = l.launched_by
            WHERE l.org_id = :orgId
            ORDER BY l.launched_at DESC, l.id
            """, nativeQuery = true)
    List<LedgerRow> ledgerRows(@Param("orgId") UUID orgId);

    interface LedgerRow {
        UUID getId();
        UUID getCohortId();
        String getCohortName();
        java.time.Instant getLaunchedAt();
        String getLaunchedByName();
    }

    /**
     * The BILLING ROOT of any org: itself for a root, its parent for a sub-org
     * (the hierarchy is one level deep). Same COALESCE rule as the retired
     * creation-time meter — cohorts live on sub-orgs, the plan lives on the
     * root, and a per-org meter would reset by creating a new sub-org.
     */
    @Query(value = """
            SELECT COALESCE(parent_organization_id, id) FROM organizations WHERE id = :orgId
            """, nativeQuery = true)
    UUID billingRootOf(@Param("orgId") UUID orgId);

    /**
     * Serializes concurrent launches for one billing family: row-locks the
     * root org for the rest of the transaction, so two launches racing for the
     * last quota slot queue up and the second one sees the first's committed
     * ledger row. The check + insert MUST run after this in the same
     * transaction (spec §8: "check fires once, on the transition, in the same
     * transaction as the ledger insert").
     */
    @Query(value = "SELECT id FROM organizations WHERE id = :orgId FOR UPDATE", nativeQuery = true)
    UUID lockBillingRoot(@Param("orgId") UUID orgId);

    /** The root org's billing facts the quota verdict needs (projection below). */
    @Query(value = """
            SELECT subscription_tier AS tier, trial_ends_at AS trialEndsAt, created_at AS createdAt
            FROM organizations WHERE id = :orgId
            """, nativeQuery = true)
    java.util.Optional<RootBillingRow> rootBillingRow(@Param("orgId") UUID orgId);

    /**
     * The root org's own tier + trial expiry + age, for the quota verdict and
     * the stalled-onboarding flag. Native for the same soft-coupling reason as
     * above. (The effective tier of a family IS the root's tier — sub-orgs
     * have no billing identity.)
     */
    interface RootBillingRow {
        String getTier();
        java.time.Instant getTrialEndsAt();
        java.time.Instant getCreatedAt();
    }
}
