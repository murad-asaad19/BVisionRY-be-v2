package com.bvisionry.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bvisionry.catalog.domain.Tag;

/**
 * Access to {@link Tag}. Names are unique per org.
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByOrgIdAndNameIgnoreCase(UUID orgId, String name);

    List<Tag> findByOrgIdOrderByNameAsc(UUID orgId);

    boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);
}
