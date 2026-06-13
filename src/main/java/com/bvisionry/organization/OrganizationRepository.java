package com.bvisionry.organization;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    List<Organization> findByIsActiveTrue();
    boolean existsByNameIgnoreCase(String name);
    long countBySubscriptionTier(SubscriptionTier tier);

    /**
     * Aggregate per-org member-count + last-login in a single query.
     * Used by both the paginated platform listing (one fetch + one aggregate)
     * and the AttentionRuleService (one aggregate over every org instead of
     * 2-4 lookups per org). The LEFT JOIN ensures orgs with zero members
     * still appear with count = 0 and lastLogin = null.
     *
     * Returns rows of [organizationId(UUID), memberCount(long), lastLogin(Instant)].
     */
    @Query("""
            SELECT o.id, COUNT(u.id), MAX(u.lastLoginAt) FROM Organization o
            LEFT JOIN User u ON u.organization.id = o.id
            GROUP BY o.id
            """)
    List<Object[]> findOrgStatsAll();

    /**
     * Same projection as {@link #findOrgStatsAll()} but scoped to a page of orgs.
     * Two queries total per listing: one paginated org fetch, one aggregate over
     * the page's ids — independent of the platform-wide org count.
     */
    @Query("""
            SELECT o.id, COUNT(u.id), MAX(u.lastLoginAt) FROM Organization o
            LEFT JOIN User u ON u.organization.id = o.id
            WHERE o.id IN :orgIds
            GROUP BY o.id
            """)
    List<Object[]> findOrgStatsByIds(@Param("orgIds") List<UUID> orgIds);
    // Page<Organization> findAll(Pageable pageable) is inherited from JpaRepository.

    /** Orgs whose trial has lapsed but are still tier=PREMIUM — used by TrialExpiryJob. */
    @Query("""
        SELECT o FROM Organization o
        WHERE o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt < :now
          AND o.subscriptionTier = com.bvisionry.common.enums.SubscriptionTier.PREMIUM
        """)
    List<Organization> findLapsedTrials(@Param("now") Instant now);

    /** Count orgs currently on an active Premium trial. */
    @Query("""
        SELECT COUNT(o) FROM Organization o
        WHERE o.subscriptionTier = com.bvisionry.common.enums.SubscriptionTier.PREMIUM
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt > :now
        """)
    long countOnActiveTrial(@Param("now") Instant now);

    /** Count trials whose end date falls in [now, cutoff]. */
    @Query("""
        SELECT COUNT(o) FROM Organization o
        WHERE o.subscriptionTier = com.bvisionry.common.enums.SubscriptionTier.PREMIUM
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt BETWEEN :now AND :cutoff
        """)
    long countTrialsExpiringWithin(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** Active Premium trials whose end date falls in [now, cutoff] — used by the heads-up notifier. */
    @Query("""
        SELECT o FROM Organization o
        WHERE o.subscriptionTier = com.bvisionry.common.enums.SubscriptionTier.PREMIUM
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt BETWEEN :now AND :cutoff
        """)
    List<Organization> findEndingTrialsWithin(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** Count active orgs (excludes suspended). */
    long countByIsActiveTrue();
}
