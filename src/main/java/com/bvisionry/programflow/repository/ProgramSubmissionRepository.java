package com.bvisionry.programflow.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.ProgramSubmission;

public interface ProgramSubmissionRepository extends JpaRepository<ProgramSubmission, UUID> {

    Optional<ProgramSubmission> findByTaskIdAndUserId(UUID taskId, UUID userId);

    List<ProgramSubmission> findByUserId(UUID userId);

    List<ProgramSubmission> findByUserIdAndTaskIdIn(UUID userId, Collection<UUID> taskIds);

    List<ProgramSubmission> findByTaskIdIn(Collection<UUID> taskIds);
}
