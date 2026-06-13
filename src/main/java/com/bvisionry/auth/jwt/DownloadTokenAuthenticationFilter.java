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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates requests that carry a short-lived download JWT in the
 * {@code ?token=} query parameter. Used by the SPA when fetching PDF/XLSX
 * binaries directly from Railway (bypassing the Vercel proxy), where cookies
 * cannot ride along cross-site.
 *
 * <p>Runs <em>before</em> {@link JwtAuthenticationFilter}. If no {@code token}
 * param is present, this is a no-op pass-through and the cookie filter handles
 * auth as usual. The {@code typ} claim must equal {@link TokenType#DOWNLOAD};
 * access and refresh tokens are rejected here.
 */
@Component
@RequiredArgsConstructor
public class DownloadTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_PARAM = "token";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getParameter(TOKEN_PARAM);
        if (token != null && !token.isBlank()) {
            jwtProvider.parseAndValidate(token, TokenType.DOWNLOAD).ifPresent(claims -> {
                UUID userId = UUID.fromString(claims.getSubject());
                userRepository.findByIdWithOrganization(userId).ifPresent(user -> {
                    if (user.getOrganization() != null && !user.getOrganization().isActive()) {
                        return;
                    }
                    var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
                    var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            });
        }
        filterChain.doFilter(request, response);
    }
}
