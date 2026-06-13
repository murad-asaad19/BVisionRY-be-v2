package com.bvisionry.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * Sets, clears, and reads the HttpOnly cookies that carry the platform's auth state.
 *
 * <p>{@code SameSite} defaults to {@code Lax} (rather than Strict) so the OAuth2 callback —
 * a top-level navigation from the IdP back to our origin — keeps the cookie. Cross-site
 * SPA deployments (FE and BE on different eTLD+1, e.g. Vercel + Railway) must set
 * {@code bvisionry.security.cookies.same-site=None} together with {@code secure=true};
 * browsers drop {@code Lax} cookies on cross-site {@code fetch}/XHR. CSRF is mitigated
 * separately via {@code XSRF-TOKEN} double-submit.
 */
@Service
public class CookieService {

    public static final String ACCESS_TOKEN_COOKIE = "bv_access";
    public static final String REFRESH_TOKEN_COOKIE = "bv_refresh";
    public static final String OAUTH2_STATE_COOKIE = "oauth2_state";

    // Session cookies are scoped to "/" so they are sent on BOTH page navigations
    // and API calls. The Next.js BFF is the session authority and mints the same
    // bv_access/bv_refresh cookies at path "/" (web/src/lib/auth-cookies.ts), and the
    // SSR getSession() reads them on page requests ("/app/**") — which a "/api"-scoped
    // cookie would never reach. A single "/" path also prevents a second, narrower-path
    // duplicate (e.g. an SSO-set "/api" cookie) from shadowing the BFF's "/" cookie on
    // "/api/**" requests, which manifested as cross-user "wrong org" 403s when both existed.
    private static final String ACCESS_TOKEN_PATH = "/";
    private static final String REFRESH_TOKEN_PATH = "/";
    // The OAuth2 state cookie is a short-lived CSRF-handshake value read only at the
    // callback endpoint, so it stays narrowly scoped (not a session cookie).
    private static final String OAUTH2_STATE_PATH = "/api/auth/oauth2";
    private static final long OAUTH2_STATE_MAX_AGE_SECONDS = 600;

    private final long accessTokenMaxAgeSeconds;
    private final long refreshTokenMaxAgeSeconds;
    private final boolean secure;
    private final String sameSite;

    public CookieService(
            @Value("${bvisionry.jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${bvisionry.jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
            @Value("${bvisionry.security.cookies.secure:true}") boolean secure,
            @Value("${bvisionry.security.cookies.same-site:Lax}") String sameSite) {
        this.accessTokenMaxAgeSeconds = Duration.ofMillis(accessTokenExpirationMs).toSeconds();
        this.refreshTokenMaxAgeSeconds = Duration.ofMillis(refreshTokenExpirationMs).toSeconds();
        this.secure = secure;
        this.sameSite = sameSite;
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalStateException(
                    "Cookie configuration invalid: SameSite=None requires Secure=true. "
                            + "Set bvisionry.security.cookies.secure=true.");
        }
    }

    public void setAccessTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = baseCookie(ACCESS_TOKEN_COOKIE, token, ACCESS_TOKEN_PATH)
                .maxAge(accessTokenMaxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void setRefreshTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = baseCookie(REFRESH_TOKEN_COOKIE, token, REFRESH_TOKEN_PATH)
                .maxAge(refreshTokenMaxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void setOAuth2StateCookie(HttpServletResponse response, String state) {
        ResponseCookie cookie = baseCookie(OAUTH2_STATE_COOKIE, state, OAUTH2_STATE_PATH)
                .maxAge(OAUTH2_STATE_MAX_AGE_SECONDS)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie access = baseCookie(ACCESS_TOKEN_COOKIE, "", ACCESS_TOKEN_PATH)
                .maxAge(0)
                .build();
        ResponseCookie refresh = baseCookie(REFRESH_TOKEN_COOKIE, "", REFRESH_TOKEN_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, access.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refresh.toString());
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        return readCookie(request, ACCESS_TOKEN_COOKIE);
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        return readCookie(request, REFRESH_TOKEN_COOKIE);
    }

    public Optional<String> readOAuth2State(HttpServletRequest request) {
        return readCookie(request, OAUTH2_STATE_COOKIE);
    }

    private static Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        if (cookie == null) {
            return Optional.empty();
        }
        String value = cookie.getValue();
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path);
    }
}
