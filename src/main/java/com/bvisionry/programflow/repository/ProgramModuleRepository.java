package com.bvisionry.programflow.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramModule;

public interface ProgramModuleRepository extends JpaRepository<ProgramModule, UUID> {

    List<ProgramModule> findByCohortIdOrderByPositionAsc(UUID cohortId);

    /** SCHEDULED modules whose unlock time passed and whose unlock push hasn't gone out. */
    List<ProgramModule> findByLockModeAndUnlockAtLessThanEqualAndUnlockNotifiedAtIsNull(
            ModuleLockMode lockMode, OffsetDateTime now);
}
