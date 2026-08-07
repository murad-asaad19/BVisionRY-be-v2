package com.bvisionry.auth;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.dto.LoginRequest;
import com.bvisionry.auth.sso.SsoHandshakeController;
import com.bvisionry.auth.sso.SsoRegistrationService;
import com.bvisionry.common.errortracking.ErrorEventRecorder;
import com.bvisionry.common.exception.AuthenticationException;
import com.bvisionry.common.exception.GlobalExceptionHandler;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.config.FrontendUrls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The per-account login backoff as the WIRE sees it.
 *
 * <p>The point of these tests is not that a counter increments — {@code RateLimitServiceTest}
 * owns that — it is that the refusal a throttled caller receives cannot be used to tell a
 * real account from an imaginary one. The service is REAL here (in-memory mode, no Redis
 * wired) and only its collaborators are stubbed, so the assertions are about actual
 * response bytes rather than about how the mock was called.
 */
class AuthControllerLoginBackoffTest {

    /** Exists: the stub below authenticates it when the password is right. */
    private static final String REAL_EMAIL = "real@example.com";
    /** Does not exist: the stub rejects it whatever the password. */
    private static final String GHOST_EMAIL = "ghost@example.com";
    private static final String GOOD_PASSWORD = "Password123!";

    private MockMvc mvc;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = buildMvc(rateLimitService(1000));
    }

    /** @param authPerMinute the OUTER per-IP ceiling, raised out of the way unless under test. */
    private RateLimitService rateLimitService(int authPerMinute) {
        return new RateLimitService(100, 100, authPerMinute, 100, 100, 100, 100, 100, 100, 100,
                100, 100, 100, 5, 900);
    }

    private MockMvc buildMvc(RateLimitService rateLimitService) {
        authService = mock(AuthService.class);
        when(authService.login(any(LoginRequest.class), any())).thenAnswer(invocation -> {
            LoginRequest request = invocation.getArgument(0);
            if (REAL_EMAIL.equals(request.email()) && GOOD_PASSWORD.equals(request.password())) {
                return new AuthResponse(null, "access-token", "refresh-token");
            }
            // Exactly what AuthService does for a wrong password AND for an unknown
            // address: one indistinguishable rejection.
            throw new AuthenticationException("Invalid email or password");
        });
        when(authService.refresh(anyString(), any()))
                .thenReturn(new AuthResponse(null, "access-token", "refresh-token"));

        SsoRegistrationService ssoRegistrations = mock(SsoRegistrationService.class);
        when(ssoRegistrations.findByEmail(any())).thenReturn(Optional.empty());
        FrontendUrls frontendUrls = mock(FrontendUrls.class);
        when(frontendUrls.path(any())).thenReturn("https://app.example.com/login");

        CookieService cookieService = mock(CookieService.class);
        when(cookieService.readRefreshToken(any())).thenReturn(Optional.of("refresh-token"));

        AuthController controller = new AuthController(
                authService,
                mock(PasswordResetService.class),
                rateLimitService,
                new ClientIpResolver(0, ""),
                cookieService);

        return MockMvcBuilders
                .standaloneSetup(controller, new SsoHandshakeController(ssoRegistrations, frontendUrls))
                .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorEventRecorder.class)))
                .build();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    /** Drives the free budget dry and returns the first throttled response. */
    private MvcResult throttle(String email) throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(login(email, "wrong-" + i).getResponse().getStatus())
                    .as("attempt %d for %s is still answered as a plain credential failure", i + 1, email)
                    .isEqualTo(401);
        }
        return login(email, "wrong-final");
    }

    /**
     * The whole reason this layer is keyed on the SUBMITTED address rather than on a
     * resolved user: if only real accounts throttled, the 429 itself would answer
     * "does this address have an account here?" for anyone patient enough to ask.
     */
    @Test
    void throttledRefusalIsIndistinguishableBetweenRealAndUnknownAccounts() throws Exception {
        MvcResult real = throttle(REAL_EMAIL);
        MvcResult ghost = throttle(GHOST_EMAIL);

        assertThat(real.getResponse().getStatus()).isEqualTo(429);
        assertThat(ghost.getResponse().getStatus()).isEqualTo(real.getResponse().getStatus());
        assertThat(ghost.getResponse().getContentType()).isEqualTo(real.getResponse().getContentType());
        assertThat(ghost.getResponse().getHeaderNames()).isEqualTo(real.getResponse().getHeaderNames());
        // The body is byte-identical apart from the `timestamp` every ProblemDetail carries.
        assertThat(withoutTimestamp(ghost)).isEqualTo(withoutTimestamp(real));
        assertThat(withoutTimestamp(real)).contains("Too many failed sign-in attempts. Try again later.");
        // …and in particular it names neither the address nor the attempt count.
        assertThat(withoutTimestamp(real)).doesNotContain(REAL_EMAIL);
    }

    /** The refusal must also arrive at the SAME attempt for both — timing is an oracle too. */
    @Test
    void throttleTriggersAtTheSameAttemptForRealAndUnknownAccounts() throws Exception {
        assertThat(firstThrottledAttempt(REAL_EMAIL)).isEqualTo(firstThrottledAttempt(GHOST_EMAIL));
    }

    private int firstThrottledAttempt(String email) throws Exception {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (login(email, "wrong-" + attempt).getResponse().getStatus() == 429) {
                return attempt;
            }
        }
        throw new AssertionError("never throttled " + email);
    }

    @Test
    void aSuccessfulSignInClearsTheAccountsFailureHistory() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(REAL_EMAIL, "wrong-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(login(REAL_EMAIL, GOOD_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        // A full budget again: six more failures are answered 401, not 429.
        for (int i = 0; i < 6; i++) {
            assertThat(login(REAL_EMAIL, "again-" + i).getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void throttlingOneAccountLeavesEveryOtherAccountSignable() throws Exception {
        throttle(GHOST_EMAIL);

        assertThat(login(REAL_EMAIL, GOOD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * The account layer is INNER: the per-IP ceiling that predates it still fires on its
     * own terms, on requests the account layer would happily have let through.
     */
    @Test
    void theOuterPerIpCeilingStillFiresIndependently() throws Exception {
        mvc = buildMvc(rateLimitService(2));

        assertThat(login(REAL_EMAIL, GOOD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(REAL_EMAIL, GOOD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(REAL_EMAIL, GOOD_PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    /** Password login only. A throttled address must still be able to sign in with SSO. */
    @Test
    void ssoAndRefreshAreUntouchedByAThrottledPasswordAccount() throws Exception {
        throttle(REAL_EMAIL);

        assertThat(mvc.perform(get("/api/auth/sso/handshake/start").param("email", REAL_EMAIL))
                .andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(mvc.perform(post("/api/auth/refresh")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private static String withoutTimestamp(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<any>\"");
    }
}
