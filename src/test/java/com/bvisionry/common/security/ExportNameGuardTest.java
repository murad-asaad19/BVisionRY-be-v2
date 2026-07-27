package com.bvisionry.common.security;

import com.bvisionry.common.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard's decision table, at the level {@code ExportNameAuthorityIntegrationTest}
 * cannot reach: that test always runs with a real principal installed, so the
 * "nobody is signed in" branch — the fail-closed one — has no other cover.
 */
class ExportNameGuardTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The guard must be free to sit unconditionally at the top of every export
     * handler. If a masked export could ever throw, adding the call would be a
     * behaviour change on every request rather than only on the ones asking for
     * names — including on paths where nothing is authenticated yet.
     */
    @Test
    void masked_isANoOpEvenWithNobodyAuthenticated() {
        assertThatCode(() -> ExportNameGuard.checkShowNames(false)).doesNotThrowAnyException();
    }

    @Test
    void unmasked_withNobodyAuthenticated_isDenied() {
        assertThatThrownBy(() -> ExportNameGuard.checkShowNames(true))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("super admin");
    }

    /** An anonymous token IS "authenticated" to Spring — it must still be refused. */
    @Test
    void unmasked_withAnAnonymousToken_isDenied() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> ExportNameGuard.checkShowNames(true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unmasked_asOrgAdmin_isDenied() {
        authenticateAs(UserRole.ORG_ADMIN);

        assertThatThrownBy(() -> ExportNameGuard.checkShowNames(true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unmasked_asSuperAdmin_isAllowed() {
        authenticateAs(UserRole.SUPER_ADMIN);

        assertThatCode(() -> ExportNameGuard.checkShowNames(true)).doesNotThrowAnyException();
    }

    /** The exact shape both JWT filters install: principal + one role-named authority. */
    private static void authenticateAs(UserRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "principal", null, List.of(new SimpleGrantedAuthority(role.name()))));
    }
}
