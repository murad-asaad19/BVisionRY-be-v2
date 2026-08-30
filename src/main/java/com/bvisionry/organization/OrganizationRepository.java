package com.bvisionry.organization;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /*
     * V212 flipped the member-work FKs (enrollments, roll call, workshop
     * submissions and pre-survey responses) to ON DELETE RESTRICT, so the org
     * hard-delete — the one sanctioned full wipe — clears them explicitly
     * before the org row cascade takes courses and workshops. (Sessions are
     * NOT cascaded here: V171 moved them to platform cohorts, so an org
     * delete never touches them — only the members' own attendance rows go,
     * via the users cascade.) Raw
     * SQL on those schemas (cross-slice write seam scoped to this wipe):
     * user-owned rows already cascade off the users delete, but foreign rows
     * survive it (cross-org attendance on shared cohorts, public-course
     * enrollments, SET-NULL survey respondents) and would block the cascade.
     */

    @Modifying
    @Query(value = "DELETE FROM enrollment WHERE course_id IN (SELECT id FROM course WHERE org_id = :orgId)",
            nativeQuery = true)
    void deleteEnrollmentsUnderOrg(@Param("orgId") UUID orgId);

    @Modifying
    @Query(value = """
            DELETE FROM workshop_task_submissions WHERE task_id IN (
                SELECT t.id FROM workshop_exercise_tasks t
                JOIN workshop_exercises e ON e.id = t.exercise_id
                JOIN workshops w ON w.id = e.workshop_id
                WHERE w.org_id = :orgId)
            """, nativeQuery = true)
    void deleteWorkshopSubmissionsUnderOrg(@Param("orgId") UUID orgId);

    @Modifying
    @Query(value = "DELETE FROM survey_responses WHERE workshop_id IN (SELECT id FROM workshops WHERE org_id = :orgId)",
            nativeQuery = true)
    void deleteWorkshopSurveyResponsesUnderOrg(@Param("orgId") UUID orgId);
    List<Organization> findByIsActiveTrue();
    boolean existsByNameIgnoreCase(String name);
    long countBySubscriptionTier(SubscriptionTier tier);

    // --- Sub-organization hierarchy (one level deep) ----------------------

    /** Tenancy traversal used by OrgHierarchyAdapter: is child a direct sub-org of parent? */
    boolean existsByIdAndParentOrganizationId(UUID id, UUID parentOrganizationId);

    /** Direct children of a root org, for cascades (toggleActive / hardDelete). */
    List<Organization> findByParentOrganizationId(UUID parentOrganizationId);

    /** Direct children of a root org for the sub-org listing endpoint. */
    List<Organization> findByParentOrganizationIdOrderByNameAsc(UUID parentOrganizationId);

    /** Sub-org count for a single-org response (0 for sub-orgs — one level deep). */
    long countByParentOrganizationId(UUID parentOrganizationId);

    /**
     * Batch sub-org counts for a page of orgs — rows of [parentId(UUID), count(long)].
     * Orgs without children simply don't appear; callers default to 0.
     */
    @Query("""
            SELECT o.parentOrganization.id, COUNT(o) FROM Organization o
            WHERE o.parentOrganization.id IN :parentIds
            GROUP BY o.parentOrganization.id
            """)
    List<Object[]> countSubOrgsByParentIds(@Param("parentIds") List<UUID> parentIds);

    /**
     * Fetches an org with its parent initialized so
     * {@link Organization#effectiveSubscriptionTier()} is safe even when the
     * caller has no open session (OSIV is off) — used by PremiumFeatureGuard.
     */
    @Query("""
            SELECT o FROM Organization o
            LEFT JOIN FETCH o.parentOrganization
            WHERE o.id = :id
            """)
    Optional<Organization> findWithParentById(@Param("id") UUID id);

    /**
     * Paginated listing with the parent fetched eagerly so building
     * {@code OrganizationResponse.parentOrganizationName} for a page of
     * sub-orgs doesn't lazy-load one parent per row.
     */
    @Override
    @EntityGraph(attributePaths = "parentOrganization")
    Page<Organization> findAll(Pageable pageable);

    // Root-only counts for the SUPER_ADMIN platform KPIs — sub-orgs are an
    // internal subdivision of a customer, not a customer, so they must not
    // inflate org totals, retention, or the tier mix.
    long countByParentOrganizationIsNull();
    long countByIsActiveTrueAndParentOrganizationIsNull();
    long countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier tier);

    /**
     * Root-org counts grouped by tier — one row per tier that actually occurs.
     *
     * <p>A GROUP BY rather than one count per {@link SubscriptionTier} constant
     * on purpose. The platform dashboard previously derived "paying" by
     * subtraction ({@code totalOrgs - free}) specifically because, as its
     * comment said, an enumerated query "cannot miss a tier the way an
     * enumerated query can" — but subtraction cost the breakdown, which is how
     * the panel ended up reporting the removed {@code PREMIUM} name for every
     * paid org. Grouping keeps both properties: no tier can be missed, because
     * the database reports whichever tiers exist, and each is still named.
     *
     * @return {@code [SubscriptionTier, Long]} pairs; tiers with no orgs are absent.
     */
    @Query("""
            SELECT o.subscriptionTier, COUNT(o)
            FROM Organization o
            WHERE o.parentOrganization IS NULL
            GROUP BY o.subscriptionTier
            """)
    List<Object[]> countRootOrgsByTier();

    /** Root orgs only — the attention rules evaluate customers, not their subdivisions. */
    List<Organization> findByParentOrganizationIsNull();

    /**
     * Aggregate per-org member-count + last-login in a single query.
     * Used by both the paginated platform listing (one fetch + one aggregate)
     * and the AttentionRuleService (one aggregate over every org instead of
     * 2-4 lookups per org). The LEFT JOINs ensure orgs with zero members
     * still appear with count = 0 and lastLogin = null.
     *
     * Members of sub-orgs roll up into their parent's row: since every root org
     * gets a default "General" sub-org that holds its people, a direct-only
     * count would report 0 for practically every customer. DISTINCT is required
     * because a root org's direct members repeat once per child row.
     *
     * Returns rows of [organizationId(UUID), memberCount(long), lastLogin(Instant)].
     */
    @Query("""
            SELECT o.id, COUNT(DISTINCT u.id), MAX(u.lastLoginAt) FROM Organization o
            LEFT JOIN Organization c ON c.parentOrganization.id = o.id
            LEFT JOIN User u ON u.organization.id = o.id OR u.organization.id = c.id
            GROUP BY o.id
            """)
    List<Object[]> findOrgStatsAll();

    /**
     * Same projection as {@link #findOrgStatsAll()} but scoped to a page of orgs.
     * Two queries total per listing: one paginated org fetch, one aggregate over
     * the page's ids — independent of the platform-wide org count.
     */
    @Query("""
            SELECT o.id, COUNT(DISTINCT u.id), MAX(u.lastLoginAt) FROM Organization o
            LEFT JOIN Organization c ON c.parentOrganization.id = o.id
            LEFT JOIN User u ON u.organization.id = o.id OR u.organization.id = c.id
            WHERE o.id IN :orgIds
            GROUP BY o.id
            """)
    List<Object[]> findOrgStatsByIds(@Param("orgIds") List<UUID> orgIds);
    // Page<Organization> findAll(Pageable pageable) is inherited from JpaRepository.

    /** Orgs whose trial has lapsed but are still on a PAID tier — used by TrialExpiryJob. */
    @Query("""
        SELECT o FROM Organization o
        WHERE o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt < :now
          AND o.subscriptionTier <> com.bvisionry.common.enums.SubscriptionTier.FREE
        """)
    List<Organization> findLapsedTrials(@Param("now") Instant now);

    /** Count orgs currently on an active Premium trial. */
    @Query("""
        SELECT COUNT(o) FROM Organization o
        WHERE o.subscriptionTier <> com.bvisionry.common.enums.SubscriptionTier.FREE
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt > :now
        """)
    long countOnActiveTrial(@Param("now") Instant now);

    /** Count trials whose end date falls in [now, cutoff]. */
    @Query("""
        SELECT COUNT(o) FROM Organization o
        WHERE o.subscriptionTier <> com.bvisionry.common.enums.SubscriptionTier.FREE
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt BETWEEN :now AND :cutoff
        """)
    long countTrialsExpiringWithin(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** Active Premium trials whose end date falls in [now, cutoff] — used by the heads-up notifier. */
    @Query("""
        SELECT o FROM Organization o
        WHERE o.subscriptionTier <> com.bvisionry.common.enums.SubscriptionTier.FREE
          AND o.trialEndsAt IS NOT NULL
          AND o.trialEndsAt BETWEEN :now AND :cutoff
        """)
    List<Organization> findEndingTrialsWithin(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** Count active orgs (excludes suspended). */
    long countByIsActiveTrue();
}
