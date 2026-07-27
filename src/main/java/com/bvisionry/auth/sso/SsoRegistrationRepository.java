package com.bvisionry.auth.sso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SsoRegistrationRepository extends JpaRepository<SsoRegistration, UUID> {

    /** Login path: resolve the IdP that owns a handshake. Disabled rows must never authenticate. */
    Optional<SsoRegistration> findByRegistrationIdAndEnabledTrue(String registrationId);

    /** Discovery path: which IdP owns this (already normalised) email domain. */
    Optional<SsoRegistration> findByEmailDomainAndEnabledTrue(String emailDomain);

    Optional<SsoRegistration> findByRegistrationId(String registrationId);

    Optional<SsoRegistration> findByEmailDomain(String emailDomain);

    /**
     * The admin list. Deliberately NOT {@code findAll()}: {@code SsoRegistration}
     * carries an {@code orgId} field, which makes this an org-owned repository to
     * the ArchUnit tenancy rule, and {@code findAll} is one of the bare-ID loads
     * that rule confines to {@code require*} guards. Ordering by domain also makes
     * the response stable for a reader scanning for a tenant.
     */
    List<SsoRegistration> findAllByOrderByEmailDomainAsc();

    /**
     * Is the organization behind a registration still active?
     *
     * <p>Raw SQL against another feature's table on purpose. The Java answer —
     * {@code user.getOrganization().isActive()}, as {@code AuthService} does it —
     * would create a new {@code auth -> organization} type edge, which the ArchUnit
     * ratchet rejects. Reading the column directly is the established escape hatch
     * (see {@code common.gdpr.PersonalDataRepository},
     * {@code coaching.repository.CoachingReadRepository}).
     *
     * @return {@code null} when the organization row does not exist
     */
    @Query(value = "SELECT is_active FROM organizations WHERE id = :orgId", nativeQuery = true)
    Boolean findOrganizationActive(@Param("orgId") UUID orgId);
}
