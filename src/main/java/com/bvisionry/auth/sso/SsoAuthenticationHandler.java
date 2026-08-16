package com.bvisionry.auth.sso;

import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.common.exception.SsoFlowException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.config.FrontendUrls;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The seam between Spring Security's SSO handshake and this platform's session
 * model.
 *
 * <p>The handshake filter chain is the ONLY stateful surface in the application:
 * the SAML {@code InResponseTo} record and the OAuth2 authorization request both
 * live in an {@code HttpSession} for the ~30 seconds of the round trip, because
 * that is where the DSL's default repositories put them and replacing them with
 * hand-written cookie stores would mean hand-writing replay protection. This
 * handler is where that ends: on every outcome it destroys the handshake session
 * and clears the SecurityContext, so what the browser leaves with is the same
 * pair of stateless JWT cookies a password login mints, and the main filter chain
 * stays {@code STATELESS}.
 *
 * <p>Session issuance goes through {@link AuthService#issueSession} — the single
 * session-minting seam — so token semantics, expiry and refresh-token bookkeeping
 * are identical to every other sign-in.
 */
@Component
@RequiredArgsConstructor
public class SsoAuthenticationHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthenticationHandler.class);

    /**
     * Attribute names IdPs use for an email address, most specific first. The URN
     * is LDAP's {@code mail}; the schemas.xmlsoap.org one is what AD FS and
     * Entra ID emit. Falling back to the NameID last is deliberate: the
     * {@code emailAddress} NameID format is extremely common and is the only thing
     * some minimal IdP configurations send.
     */
    private static final List<String> SAML_EMAIL_ATTRIBUTES = List.of(
            "email",
            "mail",
            "urn:oid:0.9.2342.19200300.100.1.3",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");

    private final SsoLoginService ssoLoginService;
    private final CookieService cookieService;
    private final FrontendUrls frontendUrls;
    private final ClientIpResolver clientIpResolver;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String registrationId = registrationIdOf(authentication);
        String email = emailOf(authentication);
        String avatarUrl = avatarOf(authentication);
        endHandshake(request);

        if (registrationId == null) {
            // Unreachable with the two configured providers; fail closed rather than
            // guess which registration a future one belongs to.
            log.error("SSO handshake produced an unrecognised authentication: {}",
                    authentication.getClass().getName());
            redirectToLogin(response, "sso_error");
            return;
        }

        try {
            AuthResponse auth = ssoLoginService.completeLogin(registrationId, email, avatarUrl,
                    AuthService.ClientContext.of(request.getHeader("User-Agent"),
                            clientIpResolver.resolve(request)));
            // Tokens travel ONLY in HttpOnly cookies — never in the redirect URL, where
            // the 7-day refresh token would land in history, Referer and proxy logs.
            cookieService.setAccessTokenCookie(response, auth.token());
            cookieService.setRefreshTokenCookie(response, auth.refreshToken());
            response.sendRedirect(frontendUrls.path("/auth/callback?sso=success"));
        } catch (SsoFlowException refused) {
            // An expected authorization refusal (domain, platform account, other org,
            // suspended tenant): the user sees a code the login page can explain.
            log.warn("Enterprise SSO refused for registration {}: {}", registrationId, refused.getErrorCode());
            redirectToLogin(response, refused.getErrorCode());
        } catch (RuntimeException unexpected) {
            log.error("Enterprise SSO sign-in failed for registration {}: {}",
                    registrationId, unexpected.getMessage(), unexpected);
            redirectToLogin(response, "sso_error");
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // Everything the library rejects lands here: a bad signature, a stale
        // InResponseTo, a wrong audience, an unknown registration. The specific
        // reason is logged and NOT shown — telling an attacker which check failed is
        // free reconnaissance, and a legitimate user cannot act on it either way.
        endHandshake(request);
        log.warn("Enterprise SSO handshake rejected: {}", exception.getMessage());
        redirectToLogin(response, "sso_error");
    }

    /**
     * Destroy the handshake session and the in-flight security context. Without
     * this the ~30-second handshake session would survive as a real server-side
     * session, which is precisely the property the main chain's
     * {@code STATELESS} policy exists to guarantee the app does not have.
     */
    private static void endHandshake(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private void redirectToLogin(HttpServletResponse response, String errorCode) throws IOException {
        response.sendRedirect(frontendUrls.path(
                "/login?error=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8)));
    }

    static String registrationIdOf(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauth2) {
            return oauth2.getAuthorizedClientRegistrationId();
        }
        if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
            return saml.getRelyingPartyRegistrationId();
        }
        return null;
    }

    static String emailOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            // preferred_username is the Entra ID fallback when the email scope is
            // present but the mailbox attribute is not populated; it holds the UPN,
            // which is an address. The domain gate re-checks whatever comes out.
            return firstNonBlank(oidc.getEmail(), oidc.getPreferredUsername());
        }
        if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
            for (String attribute : SAML_EMAIL_ATTRIBUTES) {
                String value = asString(saml.getFirstAttribute(attribute));
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return saml.getName();
        }
        return null;
    }

    private static String avatarOf(Authentication authentication) {
        // SAML has no conventional picture attribute; only OIDC carries one.
        return authentication.getPrincipal() instanceof OidcUser oidc ? oidc.getPicture() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
