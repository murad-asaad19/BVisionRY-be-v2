package com.bvisionry.config;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-IP ceiling on SSO discovery, and — just as importantly — the paths it
 * must NOT apply to.
 *
 * <p>The exemption is the load-bearing half. An enterprise customer's staff share
 * one NAT egress address, so extending a per-IP login budget over the
 * authorize/ACS/callback hops would turn "the office signs in on Monday morning"
 * into a self-inflicted outage, refusing users mid-handshake AFTER their identity
 * provider had already authenticated them. Widening the filter to the whole
 * handshake is a one-word change and would otherwise pass unnoticed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoHandshakeRateLimitFilterTest {

    @Mock private RateLimitService rateLimitService;
    @Mock private ClientIpResolver clientIpResolver;
    @Mock private FilterChain chain;

    private SsoHandshakeRateLimitFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new SsoHandshakeRateLimitFilter(rateLimitService, clientIpResolver);
        response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.7");
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void discoveryIsCountedAgainstTheCallersLoginBudget() throws Exception {
        filter.doFilter(request("/api/auth/sso/handshake/start"), response, chain);

        verify(rateLimitService).checkAuthLimit("203.0.113.7");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anExhaustedBudgetIsRefusedWithoutReachingTheHandshake() throws Exception {
        doThrow(new RateLimitExceededException("Too many attempts"))
                .when(rateLimitService).checkAuthLimit(any());

        filter.doFilter(request("/api/auth/sso/handshake/start"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
    }

    /**
     * Every hop the customer's own users traverse, all of them exempt. These arrive
     * from the shared corporate egress address; a shared per-IP budget across them
     * is an outage, not a control.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/sso/handshake/saml2/authenticate/orgb-okta",
            "/api/auth/sso/handshake/saml2/acs/orgb-okta",
            "/api/auth/sso/handshake/oauth2/authorization/orgb-entra",
            "/api/auth/sso/handshake/oauth2/callback/orgb-entra",
    })
    void theHandshakeHopsThemselvesAreNeverRateLimited(String uri) throws Exception {
        filter.doFilter(request(uri), response, chain);

        verifyNoInteractions(rateLimitService);
        verify(chain).doFilter(any(), any());
    }
}
