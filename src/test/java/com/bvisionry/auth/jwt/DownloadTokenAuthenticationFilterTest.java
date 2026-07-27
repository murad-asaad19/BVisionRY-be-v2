package com.bvisionry.auth.jwt;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadTokenAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-must-be-32-bytes-minimum-for-hmac-sha-256!!";

    /**
     * An ARBITRARY path outside {@code /api/auth} — deliberately not a real route,
     * because the filter has no path allowlist and these tests must not imply one.
     * (The real insights export is {@code /api/organizations/{orgId}/org-insights/{reportId}/pdf};
     * nothing is served from {@code /api/insights}.)
     */
    private static final String DOWNLOAD_PATH = "/api/insights/pdf";

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
        user.setStatus(UserStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

        MockHttpServletRequest req = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
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

    /** HEAD is safe and browsers issue it for downloads; it stays allowed. */
    @Test
    void headRequest_authenticatesLikeGet() throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("HEAD", DOWNLOAD_PATH);
        req.setParameter("token", token);

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void accessTokenPresentedAsDownloadToken_isRejected() throws Exception {
        // Strict typ-claim guard: an access token in ?token= must NOT auth.
        //
        // The stub is what makes this test MEAN anything. Without it the mock
        // returns Optional.empty() and the authenticating branch is unreachable
        // whatever the filter does — the assertion then holds even with the typ
        // check deleted entirely (measured: the guard was disabled and this test
        // stayed green). With the stub, deleting the guard authenticates an
        // ACCESS token here and reddens this line (measured: it does).
        //
        // `lenient()` is REQUIRED, not laziness: when the guard works the filter
        // never reaches the repository, so a strict stub is "unnecessary" and
        // Mockito errors the test for the very reason it is passing. Strictness
        // cannot police reachability on a negative test — only the mutation can.
        String access = jwtProvider.generateAccessToken(user);
        lenient().when(userRepository.findByIdWithOrganization(user.getId()))
                .thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
        req.setParameter("token", access);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void garbageToken_isRejectedAndChainContinues() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
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

        MockHttpServletRequest req = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * The divergence this filter was fixed for: JwtAuthenticationFilter refuses any
     * non-ACTIVE user, and an unexpired download token must not be a way around that.
     * Both filters now ask AuthenticationEligibility.
     */
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void nonActiveUser_doesNotAuthenticate(UserStatus status) throws Exception {
        user.setStatus(status);
        String token = jwtProvider.generateDownloadToken(user);
        when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    /**
     * A URL credential lands in access logs, browser history and Referer headers, so it
     * must never drive a state change. Unsafe methods are refused before the token is
     * even read.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsafeMethod_doesNotAuthenticate(String method) throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        lenient().when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/organizations/o/members");
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    /**
     * GET /api/auth/download-token mints a fresh download token for whoever is
     * authenticated. If a download token could authenticate it, a leaked one would renew
     * itself forever and the 60s TTL — the only real protection a URL credential has —
     * would mean nothing.
     */
    @Test
    void downloadToken_cannotMintAnotherDownloadToken() throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        lenient().when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/download-token");
        req.setParameter("token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    /** Matrix parameters must not smuggle a request past the /api/auth exclusion. */
    @Test
    void authSurfaceExclusion_isNotBypassedByMatrixParameters() throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        lenient().when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth;x=1/download-token");
        req.setParameter("token", token);

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
