package com.bvisionry.programflow.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.CohortLaunchGrant;

/** SUPER_ADMIN launch-quota overrides (spec §8), keyed by billing-root org. */
public interface CohortLaunchGrantRepository extends JpaRepository<CohortLaunchGrant, UUID> {

    /** Grants created in the current calendar period (+1 launch each). */
    long countByOrgIdAndCreatedAtGreaterThanEqual(UUID orgId, OffsetDateTime since);

    /** All-time grants — trial orgs meter over their lifetime. */
    long countByOrgId(UUID orgId);

    /** §7b audit view, newest first, with the granting operator's name. */
    @Query(value = """
            SELECT g.id AS id, g.created_at AS createdAt, u.name AS grantedByName, g.note AS note
            FROM cohort_launch_grants g
            LEFT JOIN users u ON u.id = g.granted_by
            WHERE g.org_id = :orgId
            ORDER BY g.created_at DESC, g.id
            """, nativeQuery = true)
    List<GrantRow> grantRows(@Param("orgId") UUID orgId);

    interface GrantRow {
        UUID getId();
        java.time.Instant getCreatedAt();
        String getGrantedByName();
        String getNote();
    }
}
