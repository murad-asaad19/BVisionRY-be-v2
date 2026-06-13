package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineAutoAssignmentRepository extends JpaRepository<PipelineAutoAssignment, UUID> {

    @Query("""
            SELECT r FROM PipelineAutoAssignment r
            JOIN FETCH r.pipeline
            WHERE r.organization.id = :orgId
            ORDER BY r.createdAt DESC
            """)
    List<PipelineAutoAssignment> findByOrganizationId(@Param("orgId") UUID orgId);

    /**
     * Rules that should fire for a member of {@code userType} joining {@code orgId}:
     * either an org-wide rule (user_type IS NULL) or one matching the member's type.
     */
    @Query("""
            SELECT r FROM PipelineAutoAssignment r
            JOIN FETCH r.pipeline
            WHERE r.organization.id = :orgId
              AND (r.userType IS NULL OR r.userType = :userType)
            """)
    List<PipelineAutoAssignment> findApplicableForMember(@Param("orgId") UUID orgId,
                                                          @Param("userType") String userType);

    /** Idempotent upsert lookup. {@code userType} may be null for the org-wide rule. */
    @Query("""
            SELECT r FROM PipelineAutoAssignment r
            WHERE r.organization.id = :orgId
              AND r.pipeline.id = :pipelineId
              AND ((:userType IS NULL AND r.userType IS NULL)
                   OR r.userType = :userType)
            """)
    Optional<PipelineAutoAssignment> findRule(@Param("orgId") UUID orgId,
                                              @Param("pipelineId") UUID pipelineId,
                                              @Param("userType") String userType);

    @Modifying
    @Transactional
    void deleteByPipelineId(UUID pipelineId);

    /**
     * Eager-load the rule with its organization + pipeline graph, so callers
     * outside an open Hibernate session (e.g. AFTER_COMMIT listeners) can read
     * the related ids/status without tripping {@code LazyInitializationException}.
     */
    @Query("""
            SELECT r FROM PipelineAutoAssignment r
            JOIN FETCH r.pipeline
            JOIN FETCH r.organization
            WHERE r.id = :id
            """)
    Optional<PipelineAutoAssignment> findByIdWithOrgAndPipeline(@Param("id") UUID id);

    /**
     * Returns one row per distinct (pipeline, organization) pair that has at
     * least one auto-assign rule, as {@code [pipelineId, orgId, orgName]}.
     * Used to badge org pills in the pipeline list.
     */
    @Query("""
            SELECT DISTINCT r.pipeline.id, r.organization.id, r.organization.name
            FROM PipelineAutoAssignment r
            WHERE r.pipeline.id IN :pipelineIds
            """)
    List<Object[]> findDistinctOrgsByPipelineIds(@Param("pipelineIds") List<UUID> pipelineIds);
}
