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

    private static User persistAndAuthenticate(UserRepository userRepository, User user) {
        User saved = userRepository.save(user);
        var authorities = List.of(new SimpleGrantedAuthority(saved.getRole().name()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(saved, null, authorities));
        return saved;
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
