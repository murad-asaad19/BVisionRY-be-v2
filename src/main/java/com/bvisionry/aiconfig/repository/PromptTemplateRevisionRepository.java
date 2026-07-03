package com.bvisionry.aiconfig.repository;

import com.bvisionry.aiconfig.entity.PromptTemplateRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PromptTemplateRevisionRepository extends JpaRepository<PromptTemplateRevision, UUID> {
}
