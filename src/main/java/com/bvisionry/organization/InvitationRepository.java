package com.bvisionry.organization;

import com.bvisionry.organization.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByToken(UUID token);
    List<Invitation> findByOrganizationId(UUID organizationId);
    boolean existsByEmailAndOrganizationIdAndStatus(String email, UUID organizationId, Invitation.InvitationStatus status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByOrganizationId(UUID organizationId);
}
