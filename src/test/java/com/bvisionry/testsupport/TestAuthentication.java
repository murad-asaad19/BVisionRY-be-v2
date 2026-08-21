package com.bvisionry.testsupport;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.entity.Organization;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Authenticates the current thread as a freshly-persisted user. Mirrors what
 * {@code JwtAuthenticationFilter} would have done after a successful login
 * (User principal + a SimpleGrantedAuthority named after the role) so
 * {@code @PreAuthorize("hasAuthority('...')")} resolves the same way at test
 * time as in production.
 */
public final class TestAuthentication {

    private TestAuthentication() {}

    public static User authenticateAsSuperAdmin(UserRepository userRepository) {
        User user = new User();
        user.setEmail("test-super-admin@bvisionry.invalid");
        user.setName("Test Super Admin");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        return persistAndAuthenticate(userRepository, user);
    }

    public static User authenticateAsOrgAdmin(UserRepository userRepository, Organization organization) {
        User user = new User();
        user.setEmail("test-org-admin@bvisionry.invalid");
        user.setName("Test Org Admin");
        user.setRole(UserRole.ORG_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(organization);
        return persistAndAuthenticate(userRepository, user);
    }

    public static User authenticateAsMember(UserRepository userRepository, Organization organization) {
        User user = new User();
        user.setEmail("test-member@bvisionry.invalid");
        user.setName("Test Member");
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(organization);
        return persistAndAuthenticate(userRepository, user);
    }

    /** Installs an existing user as the authenticated principal — same shape the filter installs. */
    public static void authenticate(User user) {
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    /**
     * Upsert by email, not blind insert: the helper emails are FIXED, and a
     * non-{@code @Transactional} test class that committed one (cleanup here
     * is typically {@code @BeforeEach}-only) would otherwise make every later
     * transactional caller die on {@code users_email_key} — a suite-order
     * flake, not a real failure. Role/org/status are overwritten so a stale
     * committed row can never leak another test's tenancy into this one.
     */
    private static User persistAndAuthenticate(UserRepository userRepository, User user) {
        User target = userRepository.findByEmail(user.getEmail())
                .map(existing -> {
                    existing.setName(user.getName());
                    existing.setRole(user.getRole());
                    existing.setStatus(user.getStatus());
                    existing.setOrganization(user.getOrganization());
                    return existing;
                })
                .orElse(user);
        User saved = userRepository.save(target);
        authenticate(saved);
        return saved;
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
