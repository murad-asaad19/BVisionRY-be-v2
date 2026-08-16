package com.bvisionry.auth.sso;

import com.bvisionry.common.security.AuthorizedInSecurityConfig;
import com.bvisionry.config.FrontendUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entry point of the enterprise handshake: "which identity provider, if any,
 * owns this email address?"
 *
 * <p>It lives under {@code /api/auth/sso/handshake/**} so it falls inside the
 * dedicated SSO filter chain (see {@code SsoSecurityConfig}) alongside the SAML
 * and OIDC filters, and outside the STATELESS main chain.
 *
 * <p>It answers with a redirect rather than JSON because the whole flow is a
 * browser navigation: the frontend relays this hop, follows the {@code Location},
 * and ends up at the provider. Nothing here authenticates anybody — it only
 * routes — so an unknown domain is a redirect back to the login page with a code,
 * never a 404 that a client would have to interpret.
 */
@RestController
@RequestMapping("/api/auth/sso/handshake")
@RequiredArgsConstructor
public class SsoHandshakeController {

    private final SsoRegistrationService registrationService;
    private final FrontendUrls frontendUrls;

    /**
     * Resolve {@code email}'s domain to a registration and bounce the browser into
     * that provider's handshake.
     *
     * <p>ponytail: no rate limit. The only thing an unauthenticated caller learns
     * here is whether a domain has SSO configured, which the login page reveals by
     * design anyway; and the platform's rate limiter lives in another feature
     * package, which {@code auth} may not grow a new dependency on. Add one behind
     * a shared-kernel port if domain enumeration ever becomes a concern worth the
     * seam.
     */
    @AuthorizedInSecurityConfig("permitAll: pre-auth entry point - resolves an email domain to its "
            + "identity provider before any session exists")
    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String email) {
        String location = registrationService.findByEmail(email)
                .map(this::handshakeUrl)
                .orElseGet(() -> frontendUrls.path("/login?error=sso_no_registration"));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    /**
     * Anything under the two handshake-initiation prefixes that NO security filter
     * claimed, which means exactly one thing: the registration in the URL does not
     * resolve. Unknown slug, a registration an administrator just disabled, or a row
     * whose protocol does not match the path it was reached through.
     *
     * <p>Both protocols land here. The SAML authentication-request filter simply
     * does not match an unresolvable registration and falls through; the OIDC
     * resolver is wrapped in {@code SsoSecurityConfig} so that it returns null and
     * falls through the same way instead of throwing an anonymous 500.
     *
     * <p>Answering with the login page rather than a 404 is the point: the user who
     * reaches this is mid-sign-in and needs somewhere to go, not a status code. The
     * generic code is deliberate — which registration failed to resolve is not
     * something an anonymous caller should be able to enumerate.
     */
    @AuthorizedInSecurityConfig("permitAll: pre-auth fallback - an unresolvable registration in a "
            + "handshake URL, reached before any session exists")
    @GetMapping({"/saml2/authenticate/**", "/oauth2/authorization/**"})
    public ResponseEntity<Void> unresolvedHandshake() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendUrls.path("/login?error=sso_error"))
                .build();
    }

    /**
     * The frontend-origin URL that starts the provider's handshake. Both paths are
     * relayed to the matching backend filter by the web app; keeping the shape
     * identical for SAML and OIDC is what lets one relay route handle both.
     */
    private String handshakeUrl(SsoRegistration registration) {
        String prefix = registration.getProtocol() == SsoRegistration.Protocol.SAML
                ? "/auth/sso/enterprise/saml2/authenticate/"
                : "/auth/sso/enterprise/oauth2/authorization/";
        return frontendUrls.path(prefix + registration.getRegistrationId());
    }
}
