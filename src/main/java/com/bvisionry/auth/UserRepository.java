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
}
