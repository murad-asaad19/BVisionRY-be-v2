package com.bvisionry.auth.jwt;

import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.UserStatus;
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

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final CookieService cookieService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            jwtProvider.parseAndValidate(token, TokenType.ACCESS).ifPresent(claims -> {
                UUID userId = UUID.fromString(claims.getSubject());
                userRepository.findByIdWithOrganization(userId).ifPresent(user -> {
                    if (user.getOrganization() != null && !user.getOrganization().isActive()) {
                        return;
                    }
                    // Suspended/deactivated users must lose access immediately, not only after the
                    // access-token TTL expires. Login/refresh already require ACTIVE; mirror that here
                    // so a status change takes effect on the next request (treat as unauthenticated).
                    if (user.getStatus() != UserStatus.ACTIVE) {
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

    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return cookieService.readAccessToken(request).orElse(null);
    }
}
