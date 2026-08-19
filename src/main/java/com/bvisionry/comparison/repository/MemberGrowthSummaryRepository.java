package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.MemberGrowthSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface MemberGrowthSummaryRepository extends JpaRepository<MemberGrowthSummary, UUID> {

    /** The only lookup there is: one summary per member per cohort (V190's unique key). */
    Optional<MemberGrowthSummary> findByCohortIdAndUserId(UUID cohortId, UUID userId);

    /**
     * The §7 recompute rule, applied to the summary for the same reason it is
     * applied to the narratives it was written from: the numbers moved under it,
     * so the approval stamp has to be re-earned.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MemberGrowthSummary s
               SET s.status = com.bvisionry.comparison.domain.NarrativeStatus.DRAFT,
                   s.approvedBy = NULL, s.approvedAt = NULL
             WHERE s.cohortId = :cohortId
               AND s.status = com.bvisionry.comparison.domain.NarrativeStatus.APPROVED
            """)
    int revertApprovalsForCohort(@Param("cohortId") UUID cohortId);

    /**
     * Org-scoped {@link #revertApprovalsForCohort} — a platform cohort spans orgs.
     *
     * <p>{@code @Transactional} because one caller — narrative regeneration —
     * deliberately runs outside a transaction (it wraps model calls), and a
     * {@code @Modifying} query cannot execute without one. Recompute's call
     * simply joins its own transaction.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MemberGrowthSummary s
               SET s.status = com.bvisionry.comparison.domain.NarrativeStatus.DRAFT,
                   s.approvedBy = NULL, s.approvedAt = NULL
             WHERE s.cohortId = :cohortId
               AND s.status = com.bvisionry.comparison.domain.NarrativeStatus.APPROVED
               AND s.userId IN (:userIds)
            """)
    int revertApprovalsForCohortMembers(@Param("cohortId") UUID cohortId,
                                        @Param("userIds") Collection<UUID> userIds);
}
