package com.bvisionry.programflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.ProgramModule;

public interface ProgramModuleRepository extends JpaRepository<ProgramModule, UUID> {

    List<ProgramModule> findByOrgIdOrderByPositionAsc(UUID orgId);
}
