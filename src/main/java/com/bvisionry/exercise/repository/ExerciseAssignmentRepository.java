package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciseAssignmentRepository extends JpaRepository<ExerciseAssignment, UUID> {

    @Query("select a from ExerciseAssignment a where a.organization.id = :orgId "
            + "and a.template.id = :templateId and a.user is null")
    Optional<ExerciseAssignment> findProvision(@Param("orgId") UUID orgId,
                                               @Param("templateId") UUID templateId);

    boolean existsByOrganizationIdAndTemplateIdAndUserIsNull(UUID orgId, UUID templateId);

    @Query("select a.user.id from ExerciseAssignment a where a.organization.id = :orgId "
            + "and a.template.id = :templateId and a.user.id in :userIds "
            + "and a.programTaskId is null")
    List<UUID> findExistingAssignedUserIdsIn(@Param("orgId") UUID orgId,
                                             @Param("templateId") UUID templateId,
                                             @Param("userIds") List<UUID> userIds);

    @Query("select a from ExerciseAssignment a where a.organization.id = :orgId "
            + "and a.programTaskId is null order by a.createdAt desc")
    List<ExerciseAssignment> findDirectByOrganizationId(@Param("orgId") UUID orgId);

    @Query("select a from ExerciseAssignment a where a.organization.id = :orgId "
            + "and a.user is null order by a.createdAt desc")
    List<ExerciseAssignment> findProvisionsByOrganizationId(@Param("orgId") UUID orgId);

    @Query("select a from ExerciseAssignment a where a.organization.id = :orgId "
            + "and a.user is not null and a.programTaskId is null order by a.createdAt desc")
    List<ExerciseAssignment> findMemberAssignmentsByOrganizationId(@Param("orgId") UUID orgId);

    // Deliberately NOT filtered on programTaskId: the template delete /
    // structure-lock guard must keep counting cohort-task usage too.
    long countByTemplateId(UUID templateId);
}
