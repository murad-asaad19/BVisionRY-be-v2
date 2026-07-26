package com.bvisionry.pipeline.repository;

import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.pipeline.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByStatusOrderByUpdatedAtDesc(PipelineStatus status);
    List<Pipeline> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT p FROM Pipeline p LEFT JOIN FETCH p.pillars WHERE p.id = :id")
    Optional<Pipeline> findByIdWithPillars(@Param("id") UUID id);

    @Query("SELECT DISTINCT p FROM Pipeline p LEFT JOIN FETCH p.pillars WHERE p.id = :id")
    Optional<Pipeline> findByIdWithPillarsAndQuestions(@Param("id") UUID id);

    @Query("SELECT MAX(p.version) FROM Pipeline p WHERE p.name = :name")
    Optional<Integer> findMaxVersionByName(@Param("name") String name);

    List<Pipeline> findByStatus(PipelineStatus status);

    List<Pipeline> findByStatusAndIdIn(PipelineStatus status, List<UUID> ids);

    /**
     * How many assignments reach {@code orgId} for this pipeline — provisions and
     * per-member rows alike, in the org itself or in one of its sub-orgs (admins
     * live on the root org while assignments live in a sub-org, V136).
     *
     * <p>The same predicate as {@code AssignmentRepository#findDistinctPipelineIdsByOrganizationId}
     * seen from the pipeline side — {@code PipelineAssignmentPredicateParityTest} pins
     * the two to the same answer, because nothing else would notice if a rule
     * (grandchild orgs, soft-deleted assignments) were added to only one.
     *
     * <p>Expressed as a query rather than by calling {@code AssignmentRepository}
     * — which {@code PipelineService} already holds — because the ArchUnit ratchet
     * freezes cross-feature violations per CALL SITE, not per import: a new origin
     * method produces a new violation description that is not in the store, so
     * {@code isAssignedToOrg} delegating to the repository fails
     * {@code noCrossFeatureDependencies} even though the field and constructor
     * edges are frozen. Measured, not assumed. Depending on the schema instead of
     * on feature classes is what {@code common.programaccess.ProgramAudience} does
     * for the same reason.
     */
    @Query("""
            SELECT COUNT(a) FROM Assignment a
            WHERE a.pipeline.id = :pipelineId
              AND (a.organization.id = :orgId OR a.organization.parentOrganization.id = :orgId)
            """)
    long countAssignmentsToOrg(@Param("pipelineId") UUID pipelineId, @Param("orgId") UUID orgId);
}
