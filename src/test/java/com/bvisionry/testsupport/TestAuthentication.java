package com.bvisionry.testsupport;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Authenticates the current thread as a freshly-persisted SUPER_ADMIN. Mirrors
 * what {@code JwtAuthenticationFilter} would have done after a successful login
 * (User principal + a SimpleGrantedAuthority named after the role) so
 * {@code @PreAuthorize("hasAuthority('SUPER_ADMIN')")} resolves the same way at
 * test time as in production.
 */
public final class TestAuthentication {

    private TestAuthentication() {}

    public static User authenticateAsSuperAdmin(UserRepository userRepository) {
        User user = new User();
        user.setEmail("test-super-admin@bvisionry.invalid");
        user.setName("Test Super Admin");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
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
