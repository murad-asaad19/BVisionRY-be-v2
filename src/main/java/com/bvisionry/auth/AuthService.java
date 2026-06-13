package com.bvisionry.auth;

import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.dto.LoginRequest;
import com.bvisionry.auth.dto.RefreshTokenRequest;
import com.bvisionry.auth.dto.RegisterRequest;
import com.bvisionry.auth.dto.UserResponse;
import com.bvisionry.auth.entity.RefreshToken;
import com.bvisionry.auth.entity.User;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.auth.jwt.TokenType;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.AuthenticationException;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.web.RequestContextUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        return register(request, null);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, ClientContext context) {
        String email = request.email().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User with email " + email + " already exists");
        }

        User user = newActiveMember(email, request.name());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        User saved = userRepository.save(user);
        return issueTokens(saved, context);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        return login(request, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, ClientContext context) {
        String email = request.email().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AuthenticationException("Invalid email or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException("Account is not active");
        }

        if (user.getOrganization() != null && !user.getOrganization().isActive()) {
            throw new AuthenticationException("Your organization has been suspended. Contact support for assistance.");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return issueTokens(user, context);
    }

    /**
     * SSO sign-in. Resolves the user by email — auto-creating the account on first
     * sign-in, mirroring {@link #register} — and issues the session within a single
     * transaction so lazy associations (e.g. {@code organization}) stay initializable
     * when the response DTO is built. Organization membership is not required; a newly
     * created user is org-less until invited into one. The whole flow is one transaction,
     * so a rejected sign-in rolls back and never partially mutates the user (e.g. provider
     * linking is not persisted on failure).
     */
    @Transactional
    public AuthResponse ssoLogin(String email, String avatarUrl, String provider, ClientContext context) {
        String normalizedEmail = email.toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(() -> createSsoUser(normalizedEmail));

        // Account-takeover guard: never auto-link SSO onto a pre-existing LOCAL password
        // account. If the email already owns a password and has not previously linked an
        // SSO provider, anyone who completes SSO for that email (e.g. a stale/abandoned
        // Google account on the same address) would otherwise be silently authenticated as
        // the password user. Refuse and steer the legitimate owner through the deliberate
        // link-from-settings flow, which proves password ownership first.
        if (user.getSsoProvider() == null && user.getPasswordHash() != null) {
            throw new BadRequestException(
                    "An account with this email already exists. Sign in with your password, "
                            + "then link single sign-on from your account settings.");
        }

        // First SSO sign-in for a passwordless account pins it to this provider; a different
        // already-linked provider is rejected so neither OAuth account can silently
        // sign in as the other's user.
        if (user.getSsoProvider() == null) {
            user.setSsoProvider(provider);
        } else if (!user.getSsoProvider().equals(provider)) {
            throw new BadRequestException("This email is registered with a different sign-in provider");
        }
        if (avatarUrl != null && user.getAvatarUrl() == null) {
            user.setAvatarUrl(avatarUrl);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException("Account is not active");
        }
        if (user.getOrganization() != null && !user.getOrganization().isActive()) {
            throw new AuthenticationException("Your organization has been suspended. Contact support for assistance.");
        }

        user.setLastLoginAt(Instant.now());
        return issueTokens(user, context);
    }

    /**
     * Provision a brand-new account for a first-time SSO sign-in: an ACTIVE MEMBER with no
     * password and no organization (the user is invited into an org separately). Shares the
     * account-defaults factory with {@link #register}; the display name falls back to the
     * email local-part (matching the invitation/join-link convention) since the IdP profile
     * name isn't carried through.
     */
    private User createSsoUser(String email) {
        return userRepository.save(newActiveMember(email, email.split("@")[0]));
    }

    /**
     * Build (but don't persist) a new ACTIVE MEMBER — the single source of truth for the
     * role/status/activation defaults shared by password {@link #register} and SSO
     * {@link #createSsoUser}. Each caller then layers on a password or SSO provider.
     */
    private User newActiveMember(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setActivatedAt(Instant.now());
        return user;
    }

    @Transactional
    public AuthResponse issueSession(User user, ClientContext context) {
        return issueTokens(user, context);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        return refresh(request, null);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request, ClientContext context) {
        return refresh(request == null ? null : request.refreshToken(), context);
    }

    @Transactional
    public AuthResponse refresh(String token, ClientContext context) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("Refresh token is required");
        }

        Claims claims = jwtProvider.parseAndValidate(token, TokenType.REFRESH)
                .orElseThrow(() -> new AuthenticationException("Invalid or expired refresh token"));

        UUID jti;
        try {
            jti = JwtProvider.jtiOf(claims);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthenticationException("Invalid refresh token");
        }

        RefreshToken stored = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() -> new AuthenticationException("Refresh token not recognized"));

        Instant now = Instant.now();

        if (stored.isRevoked()) {
            // Replay of a rotated/revoked token is treated as theft: blanket-revoke this user's
            // outstanding sessions so a stolen JWT can't outlive the legitimate session.
            UUID userId = stored.getUser().getId();
            int revoked = refreshTokenRepository.revokeAllForUser(userId, now);
            log.warn("Refresh-token replay detected for user={} jti={}; revoked {} active token(s)",
                    userId, jti, revoked);
            throw new AuthenticationException("Refresh token reuse detected");
        }

        if (stored.isExpired(now)) {
            throw new AuthenticationException("Refresh token expired");
        }

        User user = stored.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException("Account is not active");
        }
        if (user.getOrganization() != null && !user.getOrganization().isActive()) {
            throw new AuthenticationException("Your organization has been suspended. Contact support for assistance.");
        }

        String accessToken = jwtProvider.generateAccessToken(user);
        JwtProvider.IssuedRefreshToken next = jwtProvider.generateRefreshToken(user);
        persistRefreshToken(user, next, context);

        stored.setRevokedAt(now);
        stored.setReplacedByJti(next.jti());
        refreshTokenRepository.save(stored);

        return new AuthResponse(UserResponse.from(user), accessToken, next.token());
    }

    /**
     * Revokes <em>all</em> refresh tokens for the inferred user (intentionally aggressive,
     * symmetric with password-change). The token is a hint only — even if it's malformed
     * or wrong-type we still revoke everything for the subject the signature identifies,
     * so logout stays idempotent and never leaks token-validation details.
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        UUID userId;
        try {
            userId = jwtProvider.getUserIdFromToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }

        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("User not found"));

        // SSO-only accounts have no password to verify, so the change-password path cannot
        // confirm the caller is the legitimate owner. Allowing it here would let a hijacked
        // session silently plant a password (a backdoor) on an account that never had one.
        // Setting an initial password must go through an explicit verified set-password /
        // account-recovery flow instead.
        if (user.getPasswordHash() == null) {
            throw new BadRequestException(
                    "This account uses single sign-on and has no password to change. "
                            + "Use account recovery to set a password, or continue signing in with SSO.");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthenticationException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Any session predating the password change must die.
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    /**
     * Used by callers (e.g. role-change flows) that need to drop every active
     * refresh token for a user without going through password change.
     */
    @Transactional
    public int revokeAllRefreshTokensForUser(UUID userId) {
        return refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    private AuthResponse issueTokens(User user, ClientContext context) {
        String accessToken = jwtProvider.generateAccessToken(user);
        JwtProvider.IssuedRefreshToken refresh = jwtProvider.generateRefreshToken(user);
        persistRefreshToken(user, refresh, context);
        return new AuthResponse(UserResponse.from(user), accessToken, refresh.token());
    }

    private void persistRefreshToken(User user, JwtProvider.IssuedRefreshToken issued, ClientContext context) {
        RefreshToken row = new RefreshToken();
        row.setJti(issued.jti());
        row.setUser(user);
        row.setIssuedAt(issued.issuedAt());
        row.setExpiresAt(issued.expiresAt());
        if (context != null) {
            row.setUserAgent(context.userAgent());
            row.setIpHash(context.ip() == null ? null : RequestContextUtils.sha256Hex(context.ip()));
        }
        refreshTokenRepository.save(row);
    }

    /**
     * Optional caller-supplied request context, recorded against new
     * refresh-token rows for forensics. Both fields nullable.
     */
    public record ClientContext(String userAgent, String ip) {
        public static ClientContext of(String userAgent, String ip) {
            return new ClientContext(userAgent, ip);
        }
    }
}
