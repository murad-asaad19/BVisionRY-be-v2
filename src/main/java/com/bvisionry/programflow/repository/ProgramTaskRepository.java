package com.bvisionry.programflow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.ProgramTask;

public interface ProgramTaskRepository extends JpaRepository<ProgramTask, UUID> {

    @Query("select t from ProgramTask t join fetch t.module where t.id = :id")
    Optional<ProgramTask> findWithModule(@Param("id") UUID id);
}
