package com.bvisionry.auth.jwt;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadTokenAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-must-be-32-bytes-minimum-for-hmac-sha-256!!";

    @Mock
    private UserRepository userRepository;

    private JwtProvider jwtProvider;
    private DownloadTokenAuthenticationFilter filter;
    private User user;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtProvider = new JwtProvider(SECRET, 60_000L, 60_000L, 60_000L, "bvisionry-api", "bvisionry-app");
        filter = new DownloadTokenAuthenticationFilter(jwtProvider, userRepository);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("ada@example.com");
        user.setRole(UserRole.MEMBER);
    }

    @Test
    void noTokenParam_passesThroughWithoutAuthenticating() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validDownloadToken_populatesSecurityContextWithUser() throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/pdf");
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(user);
        assertThat(auth.getAuthorities()).extracting(Object::toString).contains("MEMBER");
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void accessTokenPresentedAsDownloadToken_isRejected() throws Exception {
        // Strict typ-claim guard: an access token in ?token= must NOT auth.
        String access = jwtProvider.generateAccessToken(user);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/pdf");
        req.setParameter("token", access);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void garbageToken_isRejectedAndChainContinues() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/pdf");
        req.setParameter("token", "not-a-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void inactiveOrganization_doesNotAuthenticate() throws Exception {
        Organization org = new Organization();
        org.setActive(false);
        user.setOrganization(org);
        String token = jwtProvider.generateDownloadToken(user);
        when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/pdf");
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
