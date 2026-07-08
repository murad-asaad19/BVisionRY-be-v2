package com.bvisionry.organization;

import com.bvisionry.organization.entity.JoinLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JoinLinkRepository extends JpaRepository<JoinLink, UUID> {

    Optional<JoinLink> findByTokenAndActiveTrue(UUID token);

    /** The org-wide link — workshop-bound links are scoped separately. */
    Optional<JoinLink> findByOrganizationIdAndWorkshopIdIsNullAndActiveTrue(UUID organizationId);

    /**
     * A workshop-bound link, scoped by org: without the org filter an admin of
     * one org could read/revoke another org's workshop link (the workshop UUID
     * is the only key), since the controller only checks org membership.
     */
    Optional<JoinLink> findByOrganizationIdAndWorkshopIdAndActiveTrue(UUID organizationId, UUID workshopId);

    /**
     * Does this workshop belong to this org? A soft, SQL-level ownership check
     * on the workshops-owned table (the {@code workshop_id} column is already a
     * soft UUID reference) — kept native so the organization slice takes no
     * Java dependency on the workshops slice (architecture ratchet).
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM workshops WHERE id = :workshopId AND org_id = :orgId)",
            nativeQuery = true)
    boolean workshopBelongsToOrg(@Param("orgId") UUID orgId, @Param("workshopId") UUID workshopId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByOrganizationId(UUID organizationId);
}
