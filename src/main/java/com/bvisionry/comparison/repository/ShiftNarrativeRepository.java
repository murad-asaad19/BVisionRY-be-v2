package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftNarrativeRepository extends JpaRepository<ShiftNarrative, UUID> {

    List<ShiftNarrative> findByCohortIdAndUserId(UUID cohortId, UUID userId);

    List<ShiftNarrative> findByCohortIdAndUserIdAndStatus(UUID cohortId, UUID userId,
                                                          NarrativeStatus status);

    Optional<ShiftNarrative> findByCohortIdAndUserIdAndDistancePillarId(
            UUID cohortId, UUID userId, UUID distancePillarId);

    /**
     * The §7 recompute rule: approved narratives are never silently deleted or
     * silently re-approved — they return to DRAFT and lose the approval stamp
     * (an "Approved …" line on prose nobody has re-read since the numbers moved
     * is exactly the lie the gate exists to prevent). Generated/edited stamps
     * survive.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ShiftNarrative n
               SET n.status = com.bvisionry.comparison.domain.NarrativeStatus.DRAFT,
                   n.approvedBy = NULL, n.approvedAt = NULL
             WHERE n.cohortId = :cohortId
               AND n.status = com.bvisionry.comparison.domain.NarrativeStatus.APPROVED
            """)
    int revertApprovalsForCohort(@Param("cohortId") UUID cohortId);

    /**
     * The org-scoped slice of {@link #revertApprovalsForCohort}: a platform
     * cohort (spec §13) spans orgs, and an org admin's recompute may only return
     * THEIR org's approved narratives to draft. {@code userIds} must be non-empty
     * (the caller skips the whole recompute when the org has no members here).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ShiftNarrative n
               SET n.status = com.bvisionry.comparison.domain.NarrativeStatus.DRAFT,
                   n.approvedBy = NULL, n.approvedAt = NULL
             WHERE n.cohortId = :cohortId
               AND n.status = com.bvisionry.comparison.domain.NarrativeStatus.APPROVED
               AND n.userId IN (:userIds)
            """)
    int revertApprovalsForCohortMembers(@Param("cohortId") UUID cohortId,
                                        @Param("userIds") Collection<UUID> userIds);

    /**
     * Re-stamps every narrative's decline flag and band label from the pillar
     * rows the recompute just rebuilt.
     *
     * <p>Without this, a §7 band edit (widening the decline range, say) would
     * leave narratives asserting the OLD verdict: a pillar that is now a decline
     * would still be approvable with no forward-looking next step, which is
     * exactly the guardrail the config edit was supposed to start enforcing.
     * The flag is derived from the delta's SIGN, never from the band key — the
     * key is renameable, the sign is not.
     *
     * <p>Native + set-based: one statement for the whole cohort, run right after
     * the pillar rows exist again.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE shift_narratives n
               SET decline    = COALESCE(p.delta, 0) < 0,
                   band_key   = p.band_key,
                   updated_at = now()
              FROM founder_comparison_pillars p
              JOIN founder_comparisons c ON c.id = p.comparison_id
             WHERE n.cohort_id = :cohortId
               AND c.cohort_id = n.cohort_id
               AND c.user_id   = n.user_id
               AND p.distance_pillar_id = n.distance_pillar_id
            """)
    int restampBandsForCohort(@Param("cohortId") UUID cohortId);

    /**
     * Org-scoped {@link #restampBandsForCohort}: restamps only the narratives of
     * the given members (one org's slice of a platform cohort). {@code userIds}
     * must be non-empty.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE shift_narratives n
               SET decline    = COALESCE(p.delta, 0) < 0,
                   band_key   = p.band_key,
                   updated_at = now()
              FROM founder_comparison_pillars p
              JOIN founder_comparisons c ON c.id = p.comparison_id
             WHERE n.cohort_id = :cohortId
               AND n.user_id IN (:userIds)
               AND c.cohort_id = n.cohort_id
               AND c.user_id   = n.user_id
               AND p.distance_pillar_id = n.distance_pillar_id
            """)
    int restampBandsForCohortMembers(@Param("cohortId") UUID cohortId,
                                     @Param("userIds") Collection<UUID> userIds);
}
