package com.bvisionry.organization;

import com.bvisionry.organization.entity.JoinLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JoinLinkRepository extends JpaRepository<JoinLink, UUID> {

    Optional<JoinLink> findByTokenAndActiveTrue(UUID token);

    Optional<JoinLink> findByOrganizationIdAndActiveTrue(UUID organizationId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByOrganizationId(UUID organizationId);
}
