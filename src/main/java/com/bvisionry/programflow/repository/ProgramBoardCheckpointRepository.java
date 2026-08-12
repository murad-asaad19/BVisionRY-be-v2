package com.bvisionry.programflow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.ProgramBoardCheckpoint;

public interface ProgramBoardCheckpointRepository extends JpaRepository<ProgramBoardCheckpoint, UUID> {

    Optional<ProgramBoardCheckpoint> findByCohortIdAndCreatedBy(UUID cohortId, UUID createdBy);
}
