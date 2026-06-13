package com.bvisionry.aiconfig.repository;

import com.bvisionry.aiconfig.entity.AIConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AIConfigurationRepository extends JpaRepository<AIConfiguration, UUID> {

    /**
     * AIConfiguration is a singleton row. This fetches the first (and only) row.
     */
    default AIConfiguration getSingleton() {
        return findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AIConfiguration singleton row missing — check V6 migration seed"));
    }
}
