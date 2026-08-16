package com.bvisionry.auth;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByOrganizationId(UUID organizationId);
    List<User> findByOrganizationIdAndStatus(UUID organizationId, UserStatus status);
    List<User> findByOrganizationIdAndStatusAndUserType(UUID organizationId, UserStatus status, String userType);
    boolean existsByEmailAndOrganizationId(String email, UUID organizationId);
    long countByOrganizationId(UUID organizationId);
    long countByOrganizationIdAndRole(UUID organizationId, UserRole role);
    // Status-scoped count: the last-admin guard must only consider admins who
    // can actually log in. A SUSPENDED/DEACTIVATED ORG_ADMIN still has the role
    // but cannot administer the org, so counting by role alone lets the only
    // loginable admin be removed, stranding the org.
    long countByOrganizationIdAndRoleAndStatus(UUID organizationId, UserRole role, UserStatus status);
    long countByUserType(String userType);
    Optional<User> findFirstByOrganizationIdAndRole(UUID organizationId, UserRole role);
    List<User> findByRole(UserRole role);
    // Push-notification recipient resolution: only ACTIVE admins can act on a
    // notification, mirroring the status-scoped count rationale above.
    List<User> findByOrganizationIdAndRoleAndStatus(UUID organizationId, UserRole role, UserStatus status);
    long countByRole(UserRole role);

    /**
     * All users with their organization eagerly fetched — powers the platform-wide
     * "Platform Admins" view so {@link com.bvisionry.auth.dto.UserResponse#from}
     * can read the org tier without firing a query per row (N+1).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u FROM User u LEFT JOIN FETCH u.organization o LEFT JOIN FETCH o.parentOrganization")
    List<User> findAllWithOrganization();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByOrganizationId(UUID organizationId);

    // The parent org is fetched too: users loaded here become detached
    // @AuthenticationPrincipals (JwtAuthenticationFilter) and UserResponse.from
    // reads the parent's tier via Organization.effectiveSubscriptionTier().
    @org.springframework.data.jpa.repository.Query(
            "SELECT u FROM User u LEFT JOIN FETCH u.organization o LEFT JOIN FETCH o.parentOrganization "
                    + "WHERE u.id = :id")
    Optional<User> findByIdWithOrganization(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query("SELECT MAX(u.lastLoginAt) FROM User u WHERE u.organization.id = :orgId")
    Instant findMaxLastLoginByOrganizationId(@org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /**
     * The org a user belongs to, or {@code null} for an org-less (or absent) user.
     *
     * <p>Exists so {@code auth.sso} can enforce "this assertion may not name a user
     * who belongs to a different organization" without calling
     * {@code user.getOrganization()} — which would create a new
     * {@code auth -> organization} type dependency the ArchUnit ratchet rejects.
     * Native and column-level on purpose: it reads auth's OWN table and needs the
     * FK value, not the aggregate.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT organization_id FROM users WHERE id = :userId", nativeQuery = true)
    UUID findOrganizationIdByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);

    /**
     * Bind a user to an organization by id — the JIT-provisioning write for
     * enterprise SSO, where the caller holds an org UUID and must not import the
     * organization aggregate to set the association.
     *
     * <p>{@code clearAutomatically} is required, not decorative: this bypasses the
     * persistence context, so the caller MUST re-read (see
     * {@link #findByIdWithOrganization}) or it would keep handing out a User whose
     * {@code organization} is still null — and the access token embeds
     * {@code orgId} from exactly that field.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            value = "UPDATE users SET organization_id = :orgId WHERE id = :userId", nativeQuery = true)
    int assignOrganization(@org.springframework.data.repository.query.Param("userId") UUID userId,
                           @org.springframework.data.repository.query.Param("orgId") UUID orgId);
}
