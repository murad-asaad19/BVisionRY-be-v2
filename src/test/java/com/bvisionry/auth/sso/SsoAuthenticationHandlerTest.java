package com.bvisionry.auth.sso;

import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.common.exception.SsoFlowException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.core.Saml2Error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handoff from Spring Security's SSO handshake to this platform's session.
 *
 * <p>Three claims, all of which are invisible until something goes wrong in
 * production:
 * <ul>
 *   <li>the right identity comes out of two very differently shaped
 *       {@code Authentication}s (an {@code OidcUser}'s claims, a SAML principal's
 *       attributes or its NameID);</li>
 *   <li>a refusal from {@link SsoLoginService} becomes a login-page error code,
 *       and no cookie is set — a refusal that still minted a session would be the
 *       whole feature's failure mode;</li>
 *   <li>the handshake session is destroyed on EVERY outcome, which is what keeps
 *       the rest of the application stateless.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoAuthenticationHandlerTest {

    private static final String FRONTEND = "https://app.bvisionry.test";
    private static final String REGISTRATION = "orgb-idp";

    @Mock private SsoLoginService ssoLoginService;
    @Mock private CookieService cookieService;
    @Mock private ClientIpResolver clientIpResolver;

    private SsoAuthenticationHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl(FRONTEND);
        handler = new SsoAuthenticationHandler(ssoLoginService, cookieService,
                new FrontendUrls(properties), clientIpResolver);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.7");
        when(ssoLoginService.completeLogin(any(), any(), any(), any()))
                .thenReturn(new AuthResponse(null, "access-token", "refresh-token"));
    }

    private static Authentication saml(Map<String, List<Object>> attributes, String nameId) {
        DefaultSaml2AuthenticatedPrincipal principal =
                new DefaultSaml2AuthenticatedPrincipal(nameId, attributes, List.of(REGISTRATION));
        principal.setRelyingPartyRegistrationId(REGISTRATION);
        return new UsernamePasswordAuthenticationToken(principal, "assertion",
                AuthorityUtils.NO_AUTHORITIES);
    }

    private static Authentication oidc(Map<String, Object> claims) {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(),
                Instant.now().plusSeconds(60), claims);
        DefaultOidcUser user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken);
        return new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, REGISTRATION);
    }

    @Test
    void anOidcSuccessMintsTheSameCookiePairAPasswordLoginDoes() throws Exception {
        handler.onAuthenticationSuccess(request, response,
                oidc(Map.of("sub", "abc", "email", "founder@orgb.com", "picture", "https://cdn/x.png")));

        verify(ssoLoginService).completeLogin(eq(REGISTRATION), eq("founder@orgb.com"),
                eq("https://cdn/x.png"), any());
        verify(cookieService).setAccessTokenCookie(response, "access-token");
        verify(cookieService).setRefreshTokenCookie(response, "refresh-token");
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/auth/callback?sso=success");
    }

    @Test
    void oidcFallsBackToPreferredUsernameWhenNoEmailClaimIsReleased() throws Exception {
        // Entra ID with the email scope but no mailbox attribute populated: the UPN
        // is the address. The domain gate re-checks whatever comes out of here.
        handler.onAuthenticationSuccess(request, response,
                oidc(Map.of("sub", "abc", "preferred_username", "founder@orgb.com")));
        verify(ssoLoginService).completeLogin(eq(REGISTRATION), eq("founder@orgb.com"), isNull(), any());
    }

    /**
     * All four attribute names, because IdPs genuinely differ: Okta and OneLogin
     * send {@code email}, older SAML deployments send the LDAP OID or {@code mail},
     * AD FS and Entra ID send the schemas.xmlsoap.org claim. Testing one and naming
     * the test "any of the common names" would let the list be truncated to that one
     * entry with the suite still green — and every customer on the other three would
     * fall through to the NameID, which is often opaque.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "email",
            "mail",
            "urn:oid:0.9.2342.19200300.100.1.3",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
    })
    void samlReadsTheEmailAttributeUnderAnyOfTheCommonNames(String attribute) throws Exception {
        handler.onAuthenticationSuccess(request, response,
                saml(Map.of(attribute, List.of("founder@orgb.com")), "some-opaque-nameid"));
        verify(ssoLoginService).completeLogin(eq(REGISTRATION), eq("founder@orgb.com"), isNull(), any());
    }

    @Test
    void theMostSpecificEmailAttributeWinsWhenAnIdpSendsSeveral() throws Exception {
        // Entra ID commonly releases BOTH `email` and the xmlsoap claim, and they can
        // differ (mailbox vs UPN). Priority has to be decided rather than left to
        // whichever the map happens to iterate first.
        handler.onAuthenticationSuccess(request, response, saml(Map.of(
                "email", List.of("founder@orgb.com"),
                "mail", List.of("legacy@orgb.com"),
                "urn:oid:0.9.2342.19200300.100.1.3", List.of("oid@orgb.com"),
                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                        List.of("upn@orgb.com")), "nameid@orgb.com"));
        verify(ssoLoginService).completeLogin(eq(REGISTRATION), eq("founder@orgb.com"), isNull(), any());
    }

    @Test
    void samlFallsBackToTheNameIdWhenNoEmailAttributeIsSent() throws Exception {
        // The emailAddress NameID format is all some minimal IdP configurations send.
        handler.onAuthenticationSuccess(request, response, saml(Map.of(), "founder@orgb.com"));
        verify(ssoLoginService).completeLogin(eq(REGISTRATION), eq("founder@orgb.com"), isNull(), any());
    }

    @Test
    void aRefusedSignInSetsNoCookieAndCarriesItsCodeToTheLoginPage() throws Exception {
        when(ssoLoginService.completeLogin(any(), any(), any(), any()))
                .thenThrow(new SsoFlowException("sso_other_org", "belongs elsewhere"));

        handler.onAuthenticationSuccess(request, response, saml(Map.of(), "founder@orgb.com"));

        verify(cookieService, never()).setAccessTokenCookie(any(), any());
        verify(cookieService, never()).setRefreshTokenCookie(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=sso_other_org");
    }

    @Test
    void anUnexpectedFaultCollapsesToAGenericCodeRatherThanLeakingInternals() throws Exception {
        when(ssoLoginService.completeLogin(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        handler.onAuthenticationSuccess(request, response, saml(Map.of(), "founder@orgb.com"));

        verify(cookieService, never()).setAccessTokenCookie(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=sso_error");
    }

    @Test
    void anAuthenticationShapeWeDoNotRecogniseFailsClosed() throws Exception {
        handler.onAuthenticationSuccess(request, response,
                new UsernamePasswordAuthenticationToken("who?", null, AuthorityUtils.NO_AUTHORITIES));

        verify(ssoLoginService, never()).completeLogin(any(), any(), any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=sso_error");
    }

    @Test
    void aLibraryRejectionNeverReachesTheUserAsADetailedReason() throws Exception {
        // Bad signature, stale InResponseTo, wrong audience: all land here. Telling
        // an attacker which check failed is free reconnaissance.
        handler.onAuthenticationFailure(request, response,
                new Saml2AuthenticationException(new Saml2Error("invalid_signature",
                        "Assertion signature did not verify against idp.orgb.test")));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=sso_error");
        assertThat(response.getRedirectedUrl()).doesNotContain("signature");
    }

    @Test
    void theHandshakeSessionIsDestroyedOnSuccessAndOnFailureAlike() throws Exception {
        // The whole reason the SSO chain may create a session at all is that this
        // ends it. A surviving session would be real server-side state in an
        // application whose main filter chain is STATELESS.
        request.getSession(true);
        handler.onAuthenticationSuccess(request, response, saml(Map.of(), "founder@orgb.com"));
        assertThat(request.getSession(false)).isNull();

        MockHttpServletRequest failing = new MockHttpServletRequest();
        failing.getSession(true);
        handler.onAuthenticationFailure(failing, new MockHttpServletResponse(),
                new Saml2AuthenticationException(new Saml2Error("x", "y")));
        assertThat(failing.getSession(false)).isNull();
    }

    @Test
    void theInFlightSecurityContextIsClearedOnSuccessAndOnFailureAlike() throws Exception {
        // POPULATED FIRST, deliberately. The filter chain hands this handler a
        // request whose SecurityContextHolder already holds the just-authenticated
        // principal; asserting "null" against a context nobody ever filled asserts
        // nothing, and deleting clearContext() would leave the suite green.
        Authentication authenticated = saml(Map.of(), "founder@orgb.com");
        SecurityContextHolder.getContext().setAuthentication(authenticated);
        handler.onAuthenticationSuccess(request, response, authenticated);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        SecurityContextHolder.getContext().setAuthentication(authenticated);
        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new Saml2AuthenticationException(new Saml2Error("x", "y")));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }
}
