package com.bvisionry.assessment;

import com.bvisionry.assessment.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    @Query("""
            SELECT a FROM Assignment a
            LEFT JOIN FETCH a.user
            JOIN FETCH a.pipeline
            WHERE a.organization.id = :organizationId
            ORDER BY a.createdAt DESC
            """)
    List<Assignment> findByOrganizationIdOrderByCreatedAtDesc(@Param("organizationId") UUID organizationId);

    @Query("""
            SELECT a FROM Assignment a
            LEFT JOIN FETCH a.user
            JOIN FETCH a.pipeline
            WHERE a.organization.id = :organizationId
              AND a.user IS NULL
            ORDER BY a.createdAt DESC
            """)
    List<Assignment> findProvisionsByOrganizationIdOrderByCreatedAtDesc(
            @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT a FROM Assignment a
            JOIN FETCH a.user
            JOIN FETCH a.pipeline
            WHERE a.organization.id = :organizationId
              AND a.user IS NOT NULL
            ORDER BY a.createdAt DESC
            """)
    List<Assignment> findMemberAssignmentsByOrganizationIdOrderByCreatedAtDesc(
            @Param("organizationId") UUID organizationId);

    /** True when a super admin has provisioned this pipeline to the org. */
    boolean existsByOrganizationIdAndPipelineIdAndUserIsNull(UUID organizationId, UUID pipelineId);

    /**
     * The org-level provision row (user == null) for a (org, pipeline) pair, if
     * one exists. At most one can exist per the {@code uq_assignments_org_pipeline_provision}
     * partial unique index. Org admins distribute members against it; its
     * {@code deadline}/{@code maxCheckIns} are the defaults members inherit.
     */
    @Query("""
            SELECT a FROM Assignment a
            WHERE a.organization.id = :orgId
              AND a.pipeline.id = :pipelineId
              AND a.user IS NULL
            """)
    Optional<Assignment> findProvision(@Param("orgId") UUID orgId,
                                       @Param("pipelineId") UUID pipelineId);

    @Query("SELECT a FROM Assignment a WHERE a.organization.id = :orgId AND a.pipeline.id = :pipelineId")
    List<Assignment> findByOrganizationIdAndPipelineId(
            @Param("orgId") UUID orgId,
            @Param("pipelineId") UUID pipelineId);

    /** Per-member dedup lookup. */
    boolean existsByOrganizationIdAndPipelineIdAndUserId(UUID organizationId, UUID pipelineId, UUID userId);

    /**
     * Finds the assignment for (org, pipeline, user). Used by the embedded-assessment
     * player when lazily creating an Assignment+Submission so existing ones are
     * detected before creation.
     */
    @Query("""
            SELECT a FROM Assignment a
            JOIN FETCH a.pipeline
            JOIN FETCH a.organization
            JOIN FETCH a.user
            WHERE a.organization.id = :orgId
              AND a.pipeline.id = :pipelineId
              AND a.user.id = :userId
            """)
    Optional<Assignment> findByOrgIdAndPipelineIdAndUserId(
            @Param("orgId") UUID orgId,
            @Param("pipelineId") UUID pipelineId,
            @Param("userId") UUID userId);

    /**
     * Batched dedup lookup for {@link #existsByOrganizationIdAndPipelineIdAndUserId}.
     * Returns the subset of {@code userIds} that already have an assignment for
     * this (org, pipeline) pair, in one round-trip. Caller filters the candidate
     * member list against the returned set.
     */
    @Query("""
            SELECT a.user.id FROM Assignment a
            WHERE a.organization.id = :orgId
              AND a.pipeline.id = :pipelineId
              AND a.user.id IN :userIds
            """)
    List<UUID> findExistingAssignedUserIdsIn(@Param("orgId") UUID orgId,
                                              @Param("pipelineId") UUID pipelineId,
                                              @Param("userIds") List<UUID> userIds);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.assignment.id = :assignmentId AND s.status IN ('SUBMITTED', 'EVALUATED')")
    long countCompletedSubmissions(@Param("assignmentId") UUID assignmentId);

    List<Assignment> findByPipelineId(UUID pipelineId);

    /**
     * Returns one row per distinct (pipeline, organization) pair the pipeline
     * is manually assigned to, as {@code [pipelineId, orgId, orgName]}. Used
     * by the pipeline list to render the "Assigned To" pills; the orgId lets
     * the caller merge against auto-assign rules without name collisions.
     */
    @Query("SELECT DISTINCT a.pipeline.id, a.organization.id, a.organization.name FROM Assignment a WHERE a.pipeline.id IN :pipelineIds")
    List<Object[]> findDistinctOrgsByPipelineIds(@Param("pipelineIds") List<UUID> pipelineIds);

    /** Distinct pipelines assigned (provisioned or per-member) to an org. */
    @Query("SELECT DISTINCT a.pipeline.id FROM Assignment a WHERE a.organization.id = :orgId")
    List<UUID> findDistinctPipelineIdsByOrganizationId(@Param("orgId") UUID orgId);

    @Modifying
    @Transactional
    long deleteByOrganizationId(UUID organizationId);

    /**
     * Re-parent every assignment owned by {@code userId} in {@code fromOrgId} to
     * {@code toOrgId} in a single statement. Used by the move-member flow; the
     * unique {@code (organization_id, pipeline_id, user_id)} index can't be
     * violated because the moving user wasn't yet a member of the target org.
     * Returns the number of rows updated.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Assignment a SET a.organization.id = :toOrgId "
            + "WHERE a.user.id = :userId AND a.organization.id = :fromOrgId")
    int reassignToOrganization(@Param("userId") UUID userId,
                                @Param("fromOrgId") UUID fromOrgId,
                                @Param("toOrgId") UUID toOrgId);

    /**
     * Bulk-delete every assignment owned by {@code userId} in {@code orgId}.
     * Submissions cascade via the {@code submissions.assignment_id} FK (V33),
     * which in turn cascade their own children (answers, pillar_evaluations,
     * overall_summaries, survey_responses, pillar unlocks).
     */
    @Modifying
    @Transactional
    int deleteByUserIdAndOrganizationId(UUID userId, UUID organizationId);
}
