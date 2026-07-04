package com.bvisionry.programflow.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.ProgramSettings;

public interface ProgramSettingsRepository extends JpaRepository<ProgramSettings, UUID> {
}
