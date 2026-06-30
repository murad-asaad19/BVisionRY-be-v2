package com.bvisionry.auth;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.common.exception.AuthenticationException;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.organization.InvitationService;
import com.bvisionry.organization.JoinLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the OAuth2 SSO flow for Google.
 * Implements the authorization code flow manually (no spring-boot-starter-oauth2-client)
 * because this is a SPA with JWT-based stateless auth.
 */
@RestController
@RequestMapping("/api/auth/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuth2Controller {

    private final AuthService authService;
    private final InvitationService invitationService;
    private final JoinLinkService joinLinkService;
    private final RestClient.Builder restClientBuilder;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final CookieService cookieService;
    private final FrontendUrls frontendUrls;

    @Value("${bvisionry.oauth2.google.client-id:}")
    private String googleClientId;
    @Value("${bvisionry.oauth2.google.client-secret:}")
    private String googleClientSecret;

    @Value("${bvisionry.oauth2.redirect-base-url:http://localhost:8080}")
    private String redirectBaseUrl;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    // ========== Initiation endpoints ==========

    @GetMapping("/google")
    public ResponseEntity<Void> initiateGoogle(
            @RequestParam(required = false) String invitation,
            @RequestParam(required = false) String join,
            HttpServletResponse response) {
        if (googleClientId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, frontendUrls.path("/login?error=google_not_configured"))
                    .build();
        }
        String state = generateAndStoreState(response);
        // Stash any pending invitation/join token so the callback can bind org membership after
        // the Google round-trip. Carried in its own short-lived, narrowly-scoped cookie (the
        // state cookie is reserved for the CSRF handshake).
        storePendingAccept(invitation, join, response);
        String redirectUri = redirectBaseUrl + "/api/auth/oauth2/google/callback";
        String url = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(googleClientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&access_type=offline"
                + "&prompt=select_account"
                + "&state=" + enc(state);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    // ========== Callback endpoints ==========

    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String error,
                                                @RequestParam(required = false) String state,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        rateLimitService.checkAuthLimit(clientIpResolver.resolve(request));

        // Consume the pending invitation/join token (stashed at initiation) up-front so it is cleared
        // on EVERY callback outcome — the early returns below, a failed sign-in, or success alike.
        // Reading it later would let a token from an abandoned/failed attempt survive its TTL and be
        // replayed into a later, unrelated sign-in.
        PendingAccept pending = readAndClearPendingAccept(request, response);

        if (error != null || code == null) {
            return redirectToFrontend("sso_denied");
        }
        if (!validateState(state, request)) {
            return redirectToFrontend("sso_error");
        }

        try {
            String redirectUri = redirectBaseUrl + "/api/auth/oauth2/google/callback";
            // Exchange code for tokens
            Map<String, Object> tokenResponse = exchangeCodeForToken(
                    "https://oauth2.googleapis.com/token",
                    googleClientId, googleClientSecret, code, redirectUri);

            String idToken = (String) tokenResponse.get("id_token");
            if (idToken == null) {
                return redirectToFrontend("token_error");
            }

            // Decode ID token payload (JWT base64 middle segment).
            // NOTE: this only base64-decodes the payload; it does NOT verify the JWS
            // signature. We rely on the fact that the id_token was just fetched over a
            // TLS-protected, server-to-server back-channel exchange directly from Google's
            // token endpoint (exchangeCodeForToken) using our confidential client secret,
            // so a forged token cannot reach this code path. We still validate the
            // security-relevant claims below before trusting any identity. Full JWKS
            // signature verification is tracked as a follow-up (see risks).
            Map<String, Object> claims = decodeJwtPayload(idToken);

            // Reject tokens not minted by Google (iss) or not issued for THIS client (aud).
            // This is the core of OIDC ID-token validation and guards against token
            // substitution / confused-deputy attacks even on the trusted back-channel.
            if (!isValidGoogleIssuer(claims.get("iss")) || !googleClientId.equals(claims.get("aud"))) {
                return redirectToFrontend("token_error");
            }

            String email = (String) claims.get("email");
            String avatar = (String) claims.get("picture");

            if (email == null) {
                return redirectToFrontend("no_email");
            }

            // Never trust an unverified email: Google sets email_verified=false for
            // accounts whose email ownership it has not confirmed, and provisioning/login
            // keys off email — an unverified address would allow account takeover.
            if (!Boolean.TRUE.equals(claims.get("email_verified"))) {
                return redirectToFrontend("email_not_verified");
            }

            AuthService.ClientContext ctx = AuthService.ClientContext.of(
                    request.getHeader("User-Agent"), clientIpResolver.resolve(request));

            // A pending invitation/join token (read above) means the user came from an accept/join
            // page: bind org membership as part of this sign-in instead of a plain login — otherwise
            // the Google user is authenticated but never added to the org.
            try {
                AuthResponse auth = switch (pending.kind()) {
                    case INVITATION -> invitationService.acceptInvitationViaSso(
                            pending.token(), email, avatar, "GOOGLE", ctx);
                    case JOIN -> joinLinkService.acceptJoinLinkViaSso(
                            pending.token(), email, avatar, "GOOGLE", ctx);
                    case NONE -> authService.ssoLogin(email, avatar, "GOOGLE", ctx);
                };
                return redirectToFrontendWithAuth(auth, response);
            } catch (BadRequestException | ResourceNotFoundException | AuthenticationException acceptError) {
                // EXPECTED membership-binding rejections only (expired/revoked invite, wrong Google
                // account, suspended/inactive org, link no longer active, …). Surface a code the
                // accept/join surface can explain. An unexpected fault (NPE, DB constraint, …) is NOT
                // caught here: it propagates to the outer handler and is logged at ERROR as sso_error,
                // so a real outage is never masked as a merely "invalid" invitation/link.
                if (pending.kind() != PendingAccept.Kind.NONE) {
                    log.warn("SSO {} acceptance failed: {}", pending.kind(), acceptError.getMessage());
                    return redirectToFrontend(pending.kind() == PendingAccept.Kind.INVITATION
                            ? "invitation_invalid" : "join_invalid");
                }
                throw acceptError;
            }

        } catch (Exception e) {
            log.error("Google OAuth2 callback failed: {}", e.getMessage(), e);
            return redirectToFrontend(mapSsoError(e));
        }
    }

    /**
     * Translate the various failure modes inside the callback into a stable
     * URL-safe error code the FE can match against. Anything we don't recognise
     * collapses to {@code sso_error} so we never leak internals.
     */
    private String mapSsoError(Exception e) {
        if (e instanceof BadRequestException) {
            return "sso_provider_mismatch";
        }
        return "no_organization".equals(e.getMessage()) ? "no_organization" : "sso_error";
    }

    // ========== Helpers ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForToken(String tokenUrl, String clientId,
                                                       String clientSecret, String code,
                                                       String redirectUri) {
        RestClient client = restClientBuilder.build();

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        return client.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Google issues its ID tokens with one of two accepted {@code iss} values.
     * See https://developers.google.com/identity/openid-connect/openid-connect#validatinganidtoken
     */
    private static boolean isValidGoogleIssuer(Object iss) {
        return "https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new RuntimeException("Invalid JWT");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        try {
            return JSON.readValue(payload, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JWT payload", e);
        }
    }

    private ResponseEntity<Void> redirectToFrontendWithAuth(AuthResponse auth, HttpServletResponse response) {
        // Auth is carried ONLY via the HttpOnly+Secure cookies set here. The access and
        // refresh tokens are deliberately NOT placed in the redirect URL: query-string
        // tokens leak into browser history, the Referer header, and proxy/CDN access logs,
        // and the 7-day refresh token in particular is long-lived. The FE callback page
        // finalizes the session by calling GET /api/auth/me (which reads the bv_access
        // cookie); it only needs a non-sensitive success signal, not the raw tokens.
        cookieService.setAccessTokenCookie(response, auth.token());
        cookieService.setRefreshTokenCookie(response, auth.refreshToken());

        String url = frontendUrls.path("/auth/callback?sso=success");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    private ResponseEntity<Void> redirectToFrontend(String error) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendUrls.path("/login?error=" + enc(error)))
                .build();
    }

    /**
     * A token carried across the OAuth round-trip so the callback can bind organization
     * membership. Encoded in the {@code oauth2_pending} cookie as {@code "<kind>:<uuid>"}.
     */
    private record PendingAccept(Kind kind, UUID token) {
        enum Kind { INVITATION, JOIN, NONE }

        static final PendingAccept NONE = new PendingAccept(Kind.NONE, null);
    }

    /**
     * Persist the pending invitation/join token (if any) for the callback. An {@code invitation}
     * param takes precedence: when present it is validated and used, and we never fall back to
     * {@code join} (binding via a join link would add the user to a different organization than the
     * invitation intended). The token is validated as a UUID and re-serialized in canonical form
     * <em>before</em> being written: a malformed value is dropped — degrading to a plain SSO login
     * rather than crashing the request when Spring rejects cookie-illegal characters in the raw
     * query param — and the canonical form is guaranteed cookie-safe. A plain initiation (no usable
     * token) clears any stale pending cookie so a token from an earlier abandoned attempt can't be
     * replayed into this sign-in.
     */
    private void storePendingAccept(String invitation, String join, HttpServletResponse response) {
        String value = pendingCookieValue(invitation, join);
        if (value != null) {
            cookieService.setOAuth2PendingCookie(response, value);
        } else {
            cookieService.clearOAuth2PendingCookie(response);
        }
    }

    private static String pendingCookieValue(String invitation, String join) {
        // Presence is tested with isEmpty (not isBlank) so a whitespace-only invitation counts as
        // PRESENT-but-invalid and is dropped, never falling back to join — matching the frontend
        // resolver and this method's "an invitation never falls back to join" contract.
        if (invitation != null && !invitation.isEmpty()) {
            UUID token = parseUuidOrNull(invitation);
            return token == null ? null : "invitation:" + token;
        }
        if (join != null && !join.isEmpty()) {
            UUID token = parseUuidOrNull(join);
            return token == null ? null : "join:" + token;
        }
        return null;
    }

    /** Parse a UUID, returning {@code null} instead of throwing on a malformed value. */
    private static UUID parseUuidOrNull(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Read, decode, and immediately expire the pending-acceptance cookie (single use). */
    private PendingAccept readAndClearPendingAccept(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookieService.readOAuth2Pending(request).orElse(null);
        cookieService.clearOAuth2PendingCookie(response);
        if (raw == null) {
            return PendingAccept.NONE;
        }
        int sep = raw.indexOf(':');
        if (sep <= 0) {
            return PendingAccept.NONE;
        }
        PendingAccept.Kind kind = switch (raw.substring(0, sep)) {
            case "invitation" -> PendingAccept.Kind.INVITATION;
            case "join" -> PendingAccept.Kind.JOIN;
            default -> PendingAccept.Kind.NONE;
        };
        if (kind == PendingAccept.Kind.NONE) {
            return PendingAccept.NONE;
        }
        UUID token = parseUuidOrNull(raw.substring(sep + 1));
        return token == null ? PendingAccept.NONE : new PendingAccept(kind, token);
    }

    private String generateAndStoreState(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        cookieService.setOAuth2StateCookie(response, state);
        return state;
    }

    private boolean validateState(String state, HttpServletRequest request) {
        if (state == null || state.isBlank()) return false;
        return cookieService.readOAuth2State(request)
                .map(state::equals)
                .orElse(false);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
