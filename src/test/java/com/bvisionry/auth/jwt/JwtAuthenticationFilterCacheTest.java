package com.bvisionry.auth.jwt;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;

import io.jsonwebtoken.Claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the principal-cache semantics of the auth filter:
 *
 * <ul>
 *   <li>Within the TTL, repeat requests authenticate WITHOUT re-querying the
 *       database (the whole point — this was a per-request query).</li>
 *   <li>Eviction (what the {@code User} entity listener fires on any update)
 *       forces a reload, so a suspension takes effect on the next request.</li>
 *   <li>TTL 0 disables caching entirely; an expired entry reloads.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterCacheTest {

    private static final String TOKEN = "access-token";

    @Mock private JwtProvider jwtProvider;
    @Mock private UserRepository userRepository;
    @Mock private CookieService cookieService;

    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        user = new User();
        user.setId(userId);
        user.setEmail("member@test.com");
        user.setName("Member");
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);

        Claims claims = mock(Claims.class);
        lenient().when(claims.getSubject()).thenReturn(userId.toString());
        lenient().when(jwtProvider.parseAndValidate(TOKEN, TokenType.ACCESS))
                .thenReturn(Optional.of(claims));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cachesPrincipalWithinTtl() throws Exception {
        var filter = filterWith(new UserPrincipalCache(Duration.ofSeconds(30)));
        when(userRepository.findByIdWithOrganization(userId)).thenReturn(Optional.of(user));

        assertThat(runRequest(filter)).isNotNull();
        assertThat(runRequest(filter)).isNotNull();

        verify(userRepository, times(1)).findByIdWithOrganization(userId);
    }

    @Test
    void evictionForcesReloadAndAppliesStatusChange() throws Exception {
        var cache = new UserPrincipalCache(Duration.ofSeconds(30));
        var filter = filterWith(cache);
        when(userRepository.findByIdWithOrganization(userId)).thenReturn(Optional.of(user));

        assertThat(runRequest(filter)).isNotNull();

        // Suspension: entity update → listener evicts → next request reloads
        // the suspended row and refuses authentication.
        user.setStatus(UserStatus.SUSPENDED);
        cache.evict(userId);

        assertThat(runRequest(filter)).isNull();
        verify(userRepository, times(2)).findByIdWithOrganization(userId);
    }

    @Test
    void ttlZeroDisablesCaching() throws Exception {
        var filter = filterWith(new UserPrincipalCache(Duration.ZERO));
        when(userRepository.findByIdWithOrganization(userId)).thenReturn(Optional.of(user));

        runRequest(filter);
        runRequest(filter);

        verify(userRepository, times(2)).findByIdWithOrganization(userId);
    }

    @Test
    void expiredEntryReloads() throws Exception {
        var filter = filterWith(new UserPrincipalCache(Duration.ofMillis(20)));
        when(userRepository.findByIdWithOrganization(userId)).thenReturn(Optional.of(user));

        runRequest(filter);
        Thread.sleep(60);
        runRequest(filter);

        verify(userRepository, times(2)).findByIdWithOrganization(userId);
    }

    @Test
    void entityListenerEvictsOnUpdate() {
        var cache = new UserPrincipalCache(Duration.ofSeconds(30));
        cache.put(userId, user);
        assertThat(cache.get(userId)).isPresent();

        new UserPrincipalCacheEvictionListener(cache).onUserChanged(user);

        assertThat(cache.get(userId)).isEmpty();
    }

    private JwtAuthenticationFilter filterWith(UserPrincipalCache cache) {
        return new JwtAuthenticationFilter(jwtProvider, userRepository, cookieService, cache);
    }

    /** Runs one request through the filter; returns the resulting authentication (null = refused). */
    private Authentication runRequest(JwtAuthenticationFilter filter) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
