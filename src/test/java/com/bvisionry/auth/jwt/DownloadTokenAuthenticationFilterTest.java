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

    /** Stand-in path variable. Compile-time constant so it can build @ValueSource strings. */
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    /** A real allowlisted export, used by the tests that are not about paths. */
    private static final String DOWNLOAD_PATH =
            "/api/organizations/" + ID + "/org-insights/" + ID + "/pdf";

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

    /**
     * The allowlist, positive half — every binary export the filter is FOR. A typo in
     * {@code DOWNLOAD_SURFACE} breaks the flow silently otherwise (401 on a real download),
     * because no client mints a download token and nothing else would notice.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/courses/founder-readiness/certificate/pdf",
            "/api/my/assessments/" + ID + "/results/pdf",
            "/api/my/assessments/" + ID + "/results/excel",
            "/api/surveys/" + ID + "/results/export.xlsx",
            "/api/organizations/" + ID + "/roi-report.pdf",
            "/api/organizations/" + ID + "/roi-report.xlsx",
            "/api/organizations/" + ID + "/org-insights/" + ID + "/pdf",
            "/api/organizations/" + ID + "/org-insights/" + ID + "/excel",
            "/api/organizations/" + ID + "/dashboard/insights/pdf",
            "/api/organizations/" + ID + "/dashboard/insights/excel",
            "/api/organizations/" + ID + "/dashboard/members/" + ID + "/results/" + ID + "/pdf",
            "/api/organizations/" + ID + "/dashboard/members/" + ID + "/results/" + ID + "/excel",
            "/api/organizations/" + ID + "/workshops/" + ID + "/answers/pdf",
            "/api/organizations/" + ID + "/workshops/" + ID + "/answers/excel",
    })
    void everyRealExportPath_authenticates(String path) throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        when(userRepository.findByIdWithOrganization(user.getId())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setParameter("token", token);

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    /**
     * The allowlist, negative half — audit finding H3. A download token is a URL
     * credential (access logs, browser history, {@code Referer}) and must not read
     * anything but a binary export, however harmless "GET only" sounds:
     *
     * <ul>
     *   <li>{@code /api/gdpr/me/export} is a COMPLETE personal-data export. It is the one
     *       export-shaped endpoint that is JSON rather than a binary and it is excluded.</li>
     *   <li>{@code /api/organizations/{orgId}/join-link} returns a redeemable secret, and
     *       {@code POST /api/join/{token}} is {@code permitAll()} + CSRF-exempt — so
     *       reading it converts a 60-second read credential into a permanent tenant
     *       account. Same shape as the invitation listing that
     *       {@code invitation_token_disclosure} closed; closed here at the filter, which
     *       covers every sibling secret-returning GET rather than one endpoint.</li>
     *   <li>An ordinary org-scoped listing stands in for "everything else".</li>
     * </ul>
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/gdpr/me/export",
            "/api/organizations/" + ID + "/join-link",
            "/api/organizations/" + ID + "/invitations",
            "/api/organizations/" + ID + "/members",
    })
    void nonExportPath_doesNotAuthenticate(String path) throws Exception {
        String token = jwtProvider.generateDownloadToken(user);
        lenient().when(userRepository.findByIdWithOrganization(user.getId()))
                .thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
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
