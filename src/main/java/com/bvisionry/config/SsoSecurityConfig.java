package com.bvisionry.config;

import com.bvisionry.auth.sso.OidcClientRegistrations;
import com.bvisionry.auth.sso.SamlRelyingPartyRegistrations;
import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.sso.SsoAuthenticationHandler;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * The enterprise SSO handshake chain — a SECOND {@link SecurityFilterChain},
 * scoped by {@code securityMatcher} to the handful of paths the SAML/OIDC round
 * trip touches and nothing else.
 *
 * <h2>Why a second chain rather than configuring the main one</h2>
 * The main chain is {@code SessionCreationPolicy.STATELESS} and must stay that
 * way — the platform's session model is a pair of stateless JWT cookies and
 * nothing else. But the SAML and OAuth2 DSLs store the in-flight
 * {@code AuthnRequest}/{@code AuthorizationRequest} in an {@code HttpSession} by
 * default, and that stored request is what {@code InResponseTo} and {@code state}
 * are validated against — i.e. it IS the replay protection. Swapping in
 * cookie-backed repositories was the alternative considered and rejected: it
 * means writing the store that anti-replay depends on, which is the one thing
 * this feature must not hand-roll.
 *
 * <p>So the handshake gets a session and only the handshake does. It lives for
 * the ~30 seconds of the round trip, is scoped to these paths, and
 * {@code SsoAuthenticationHandler} invalidates it on every outcome — success,
 * refusal and library rejection alike. Nothing downstream of the handshake is
 * stateful, and the main chain is untouched.
 *
 * <h2>Ordering</h2>
 * Highest precedence plus a margin: it must run before the main chain's
 * catch-all, and the margin keeps it off the exact value the e2e test chain uses
 * (equal {@code @Order} between two chains is an unspecified tie).
 */
@Configuration
public class SsoSecurityConfig {

    /** Every browser- and IdP-facing SSO path the backend serves, and nothing else. */
    static final String HANDSHAKE_PATHS = "/api/auth/sso/handshake/**";

    static final String OIDC_AUTHORIZATION_BASE_URI = "/api/auth/sso/handshake/oauth2/authorization";

    /**
     * {@link DefaultOAuth2AuthorizationRequestResolver} THROWS
     * {@code IllegalArgumentException} when the registration repository answers null
     * — an unknown slug, a registration an admin just disabled, a SAML row reached
     * through the OIDC path, or a transient discovery failure at the customer's IdP.
     * The redirect filter does not catch it, so it left the browser holding an
     * anonymous 500 that reads as an outage.
     *
     * <p>Returning null instead makes the filter fall through, exactly as the SAML
     * side already does for an unknown registration, and
     * {@code SsoHandshakeController#unresolvedHandshake} then answers both with the
     * login page. Nothing is weakened: a null registration could never have produced
     * an authorization request either way.
     */
    private static OAuth2AuthorizationRequestResolver forgivingResolver(
            OidcClientRegistrations registrations) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, OIDC_AUTHORIZATION_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                try {
                    return delegate.resolve(request);
                } catch (IllegalArgumentException unknownRegistration) {
                    return null;
                }
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                try {
                    return delegate.resolve(request, clientRegistrationId);
                } catch (IllegalArgumentException unknownRegistration) {
                    return null;
                }
            }
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain ssoHandshakeFilterChain(
            HttpSecurity http,
            SamlRelyingPartyRegistrations samlRegistrations,
            OidcClientRegistrations oidcRegistrations,
            SsoAuthenticationHandler ssoAuthenticationHandler,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver) throws Exception {

        // Constructed here rather than declared a @Component, deliberately. A Filter
        // BEAN is also auto-registered by Spring Boot as a container-level servlet
        // filter mapped to /*, which would have applied this handshake's per-IP
        // ceiling to every request the application serves — and the container-level
        // copy would consume OncePerRequestFilter's already-filtered marker, leaving
        // the copy inside this chain silently doing nothing. It is scoped to this
        // chain; nothing else should be able to reach it.
        SsoHandshakeRateLimitFilter rateLimit =
                new SsoHandshakeRateLimitFilter(rateLimitService, clientIpResolver);

        http
                .securityMatcher(HANDSHAKE_PATHS)
                // Outermost, so the per-IP ceiling refuses before any work happens.
                .addFilterBefore(rateLimit, DisableEncodeUrlFilter.class)
                // The SAML ACS is a cross-site form POST from the IdP; it cannot carry
                // our double-submit token and no CSRF scheme can make it. Its actual
                // protection is the signed assertion plus the InResponseTo check
                // against the AuthnRequest this server issued into the session above —
                // strictly stronger than a CSRF token. The OIDC callback is a GET whose
                // `state` is validated the same way. Scoped to these paths only; the
                // main chain's CSRF configuration is untouched.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // Declarative, and VERIFIED NOT LOAD-BEARING — recorded here so nobody
                // spends time treating it as the control it looks like. The DSL's
                // HttpSession-backed request repositories
                // (HttpSessionSaml2AuthenticationRequestRepository,
                // HttpSessionOAuth2AuthorizationRequestRepository) call
                // request.getSession(true) themselves, which is a servlet-level call
                // this policy does not gate: flipping this to STATELESS leaves the
                // handshake working and every test green (measured, not assumed).
                //
                // What actually bounds the statefulness is therefore NOT this line but
                // SsoAuthenticationHandler#endHandshake, which invalidates the session
                // on every outcome — and that IS mutation-tested. This stays at
                // IF_REQUIRED because it states the intent truthfully; do not read it
                // as an enforcement point.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Every path here is a pre-auth entry point by definition: a user in
                // the middle of proving who they are has no session yet. Authorization
                // happens after the assertion is validated, in SsoLoginService.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .saml2Login(saml -> saml
                        .relyingPartyRegistrationRepository(samlRegistrations)
                        .authenticationRequestUri("/api/auth/sso/handshake/saml2/authenticate/{registrationId}")
                        .loginProcessingUrl("/api/auth/sso/handshake/saml2/acs/{registrationId}")
                        .successHandler(ssoAuthenticationHandler)
                        .failureHandler(ssoAuthenticationHandler))
                .oauth2Login(oauth2 -> oauth2
                        .clientRegistrationRepository(oidcRegistrations)
                        .authorizationEndpoint(endpoint -> endpoint
                                .baseUri(OIDC_AUTHORIZATION_BASE_URI)
                                .authorizationRequestResolver(
                                        forgivingResolver(oidcRegistrations)))
                        .redirectionEndpoint(endpoint -> endpoint
                                .baseUri("/api/auth/sso/handshake/oauth2/callback/*"))
                        .successHandler(ssoAuthenticationHandler)
                        .failureHandler(ssoAuthenticationHandler));

        return http.build();
    }
}
