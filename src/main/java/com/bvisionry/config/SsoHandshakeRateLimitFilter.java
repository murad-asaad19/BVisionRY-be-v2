package com.bvisionry.config;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.web.ProblemDetailResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP ceiling on SSO DISCOVERY, mirroring {@link ErrorEventRateLimitFilter}.
 *
 * <p>{@code /start} is an anonymous tenant-enumeration oracle: it answers "does
 * this email domain have single sign-on, and with which provider" for any domain
 * an attacker cares to try, as fast as it can be asked. That is the surface worth
 * bounding, and it is bounded with the same {@code checkAuthLimit} bucket as
 * password login — these are all "attempts to start a session from this IP".
 *
 * <p><b>Scoped to {@code /start} and deliberately NOT to the rest of the
 * handshake.</b> Rate-limiting the authorize/ACS/callback hops per IP would be
 * actively harmful in the exact deployment this feature is sold into: an
 * enterprise customer's staff share one NAT egress address, so a shared per-IP
 * login budget turns "the office signs in on Monday morning" into a self-inflicted
 * outage — and the refusal would land mid-handshake, after the identity provider
 * has already authenticated them. Those hops need no ceiling of their own:
 * {@code OidcClientRegistrations} caches discovery, so they are no longer an
 * outbound amplifier, and each one is bounded by the IdP round trip itself.
 *
 * <p><b>Why it lives in {@code config}.</b> {@link RateLimitService} is in the
 * {@code aiconfig} feature and {@code auth.sso} may not grow a new edge to it
 * (frozen ArchUnit rule 1). {@code config} is the declared wiring package and is
 * exempt — the same reason every other rate-limit filter is wired from here.
 */
@RequiredArgsConstructor
public class SsoHandshakeRateLimitFilter extends OncePerRequestFilter {

    /** The discovery endpoint, and nothing else. See the class javadoc. */
    static final String DISCOVERY_PATH = "/api/auth/sso/handshake/start";

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!DISCOVERY_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            rateLimitService.checkAuthLimit(clientIpResolver.resolve(request));
        } catch (RateLimitExceededException ex) {
            ProblemDetailResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
