package com.bvisionry.aiconfig.repository;

import com.bvisionry.aiconfig.entity.PromptTemplate;
import com.bvisionry.common.enums.PromptType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findByPromptType(PromptType promptType);
}
