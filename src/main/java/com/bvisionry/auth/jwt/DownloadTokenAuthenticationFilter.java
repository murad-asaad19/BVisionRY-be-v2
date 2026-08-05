package com.bvisionry.auth.jwt;

import com.bvisionry.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Authenticates requests that carry a short-lived download JWT in the
 * {@code ?token=} query parameter. INTENDED for the SPA when fetching PDF/XLSX
 * binaries directly from Railway (bypassing the Vercel proxy), where cookies
 * cannot ride along cross-site — but no client uses it today (see below); every
 * export currently goes through the BFF proxy with cookies.
 *
 * <p>Runs <em>before</em> {@link JwtAuthenticationFilter}. If no {@code token}
 * param is present, this is a no-op pass-through and the cookie filter handles
 * auth as usual. The {@code typ} claim must equal {@link TokenType#DOWNLOAD};
 * access and refresh tokens are rejected here.
 *
 * <p><b>This is a URL credential.</b> It is copied into access logs, browser
 * history and {@code Referer} headers by construction, so it is treated as
 * replayable and is deliberately weaker than a cookie session:
 *
 * <ul>
 *   <li>It authenticates <b>GET and HEAD only</b>, so a leaked token cannot
 *       ITSELF issue a POST/PUT/PATCH/DELETE anywhere. The method is checked
 *       before {@code getParameter} is called so a form-encoded body is never
 *       consumed looking for a token — that ordering is deliberate but rests on
 *       statement order alone and NO TEST PINS IT (`MockHttpServletRequest`
 *       populates the parameter map directly and never parses a body, so this
 *       harness cannot; it would take a container test). Treat it as an
 *       invariant to preserve by hand when editing this method.
 *       <p><b>This bounds the token, not its consequences,</b> and the difference
 *       is not academic: a read can hand back another credential. Two instances
 *       existed. {@code invitation_token_disclosure} closed the first at the
 *       source — {@code GET /api/organizations/{orgId}/invitations} used to return
 *       raw invitation tokens, and {@code POST /api/invitations/{token}/accept} is
 *       {@code permitAll()} and CSRF-exempt, so a leaked download token bought a
 *       permanent account in the tenant via a state change it never made itself.
 *       The second, {@code GET /api/organizations/{orgId}/join-link}, has the same
 *       shape and still returns its redeemable secret — deliberately, because the
 *       org admin who owns it has to read it to share it. Both are now out of
 *       reach here for the same reason everything else is: the path allowlist
 *       below. Do not read "GET only" as "harmless" — read it as "cannot itself
 *       write".</li>
 *   <li>It authenticates <b>only the binary-export paths listed in
 *       {@link #DOWNLOAD_SURFACE}</b> — audit finding H3's first recorded remedy,
 *       "path-scope the filter". The token still carries its owner's full
 *       authorities, which is now inert: the reachable surface is fourteen
 *       exports the owner may already fetch through the BFF, and nothing else.
 *       In particular {@code GET /api/gdpr/me/export} — a complete personal-data
 *       export, and the one download-ish endpoint that is JSON rather than a
 *       binary — is NOT reachable.
 *       <p>An allowlist FAILS CLOSED: a new export not added here gets 401 when
 *       fetched with a download token (and keeps working over the cookie session,
 *       which is how every client fetches it today). That is the intended
 *       direction, and the cost is currently zero because no client mints a
 *       download token at all.</li>
 *   <li>It does not authenticate {@code /api/auth/**}. Redundant given the
 *       allowlist — no auth path is an export — and kept as the second lock on the
 *       one endpoint where a bypass compounds: that surface contains the
 *       mint endpoint {@code GET /api/auth/download-token}, which is itself a GET:
 *       without this, a leaked token could be replayed against it to mint a fresh
 *       one, and then again, indefinitely — turning a 60-second credential into a
 *       permanent one and making the TTL (the only real protection this token has)
 *       meaningless. None of {@code /api/auth} is a download, so excluding the
 *       whole prefix costs nothing.
 *       <p>PRECISELY: the matcher refuses the canonical path and its
 *       matrix-parameter variants. It does NOT match non-canonical spellings
 *       ({@code /api//auth/…}, {@code /api/./auth/…}, {@code /api/foo/../auth/…},
 *       {@code /api/auth%2Fdownload-token}, {@code /API/AUTH/…}) — those are
 *       stopped one layer out, and that layer was MEASURED against a real Tomcat
 *       rather than assumed: the first four are rejected <b>400</b> by the
 *       container before any filter runs, and the case variant routes to no
 *       handler (<b>404</b> authenticated, while the canonical path returns 200).
 *       So the exclusion holds end-to-end today, but it holds as defence in
 *       depth. It would break if the app were fronted by a normalising proxy or
 *       switched to a case-insensitive path matcher — do not read this bullet as
 *       "the matcher alone is sufficient".</li>
 *   <li>It refuses any principal {@link AuthenticationEligibility} refuses —
 *       the same predicate the cookie filter uses, so a suspended user cannot
 *       authenticate here after being refused there.</li>
 * </ul>
 *
 * <p>What is deliberately NOT done: minting with reduced authorities (H3's other
 * recorded remedy). It is not additive — the exports below sit behind
 * {@code hasAuthority('ORG_ADMIN')} and friends, so stripping authorities would
 * 403 the very flow this filter exists for. Path-scoping achieves the same
 * containment without that.
 *
 * <p>RECOMMENDED, but not an agent's call: <b>delete this filter, its mint
 * endpoint and {@link TokenType#DOWNLOAD} outright.</b> No client mints a download
 * token — the only reference in the web repo is the generated OpenAPI schema, and
 * every export goes through the BFF proxy with cookies. Deleting removes a URL
 * credential entirely rather than continuing to bound one nobody uses.
 */
@Component
@RequiredArgsConstructor
public class DownloadTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_PARAM = "token";

    /** See the class javadoc: the whole auth surface, mint endpoint included. */
    private static final RequestMatcher AUTH_SURFACE =
            PathPatternRequestMatcher.pathPattern("/api/auth/**");

    /**
     * Every binary-export endpoint in the application, and nothing else — the
     * complete set of paths a download token may authenticate.
     *
     * <p>Derived by enumerating every handler that writes a PDF/XLSX body (grep
     * {@code APPLICATION_PDF}, {@code spreadsheetml}, {@code attachment; filename}).
     * {@code GET /api/gdpr/me/export} is excluded on purpose: it produces JSON, is
     * not a browser binary fetch, and is a complete personal-data export.
     *
     * <p>Adding an export? Add it here too, or it 401s for download tokens (only —
     * the cookie session is unaffected). If this list is ever hard to keep current,
     * that is a signal to delete the capability rather than to widen the list.
     */
    private static final RequestMatcher DOWNLOAD_SURFACE = new OrRequestMatcher(
            Stream.of(
                            "/api/v1/courses/*/certificate/pdf",
                            "/api/my/assessments/*/results/pdf",
                            "/api/my/assessments/*/results/excel",
                            "/api/surveys/*/results/export.xlsx",
                            "/api/organizations/*/roi-report.pdf",
                            "/api/organizations/*/roi-report.xlsx",
                            "/api/organizations/*/org-insights/*/pdf",
                            "/api/organizations/*/org-insights/*/excel",
                            "/api/organizations/*/dashboard/insights/pdf",
                            "/api/organizations/*/dashboard/insights/excel",
                            "/api/organizations/*/dashboard/members/*/results/*/pdf",
                            "/api/organizations/*/dashboard/members/*/results/*/excel",
                            "/api/organizations/*/workshops/*/answers/pdf",
                            "/api/organizations/*/workshops/*/answers/excel")
                    .<RequestMatcher>map(PathPatternRequestMatcher::pathPattern)
                    .toList());

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (acceptsUrlToken(request)) {
            String token = request.getParameter(TOKEN_PARAM);
            if (token != null && !token.isBlank()) {
                jwtProvider.parseAndValidate(token, TokenType.DOWNLOAD).ifPresent(claims -> {
                    UUID userId = UUID.fromString(claims.getSubject());
                    userRepository.findByIdWithOrganization(userId).ifPresent(user -> {
                        boolean organizationActive = user.getOrganization() == null
                                || user.getOrganization().isActive();
                        if (!AuthenticationEligibility.mayAuthenticate(user, organizationActive)) {
                            return;
                        }
                        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
                        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Requests a download token may authenticate: safe methods, a known export, outside /api/auth. */
    private static boolean acceptsUrlToken(HttpServletRequest request) {
        String method = request.getMethod();
        return ("GET".equals(method) || "HEAD".equals(method))
                && !AUTH_SURFACE.matches(request)
                && DOWNLOAD_SURFACE.matches(request);
    }
}
