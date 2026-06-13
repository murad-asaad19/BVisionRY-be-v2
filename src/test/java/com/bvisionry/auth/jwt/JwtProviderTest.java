package com.bvisionry.auth.jwt;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    // 32 bytes — minimum for HMAC-SHA256 keys per jjwt
    private static final String SECRET = "test-secret-key-must-be-32-bytes-minimum-for-hmac-sha-256!!";
    private static final long ACCESS_TTL_MS = 60_000L;
    private static final long REFRESH_TTL_MS = 24L * 60 * 60 * 1000;
    private static final long DOWNLOAD_TTL_MS = 60_000L;

    private JwtProvider provider;
    private SecretKey signingKey;
    private User user;

    @BeforeEach
    void setUp() {
        provider = new JwtProvider(SECRET, ACCESS_TTL_MS, REFRESH_TTL_MS, DOWNLOAD_TTL_MS, "bvisionry-api", "bvisionry-app");
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("ada@example.com");
        user.setName("Ada");
        user.setRole(UserRole.MEMBER);
    }

    @Test
    void accessToken_carriesTypAndJtiClaim() {
        String token = provider.generateAccessToken(user);

        Claims claims = parse(token);
        assertThat(claims.get(JwtProvider.CLAIM_TYP, String.class)).isEqualTo("ACCESS");
        // jti is now stamped on access tokens too for traceability (Backlog L
        // item — auditors can correlate a leaked token to its issue event).
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void refreshToken_carriesTypAndJti() {
        JwtProvider.IssuedRefreshToken issued = provider.generateRefreshToken(user);

        Claims claims = parse(issued.token());
        assertThat(claims.get(JwtProvider.CLAIM_TYP, String.class)).isEqualTo("REFRESH");
        assertThat(claims.getId()).isNotNull();
        assertThat(UUID.fromString(claims.getId())).isEqualTo(issued.jti());
        // The IssuedRefreshToken bundle must agree with the JWT payload.
        assertThat(provider.getJti(issued.token())).isEqualTo(issued.jti());
    }

    @Test
    void validateToken_rejectsRefreshPresentedAsAccess() {
        // The single-arg validateToken() is the access-token check used by the
        // Bearer filter. Replaying a refresh token there must fail.
        JwtProvider.IssuedRefreshToken refresh = provider.generateRefreshToken(user);
        assertThat(provider.validateToken(refresh.token())).isFalse();
        assertThat(provider.validateToken(refresh.token(), TokenType.ACCESS)).isFalse();
        assertThat(provider.validateToken(refresh.token(), TokenType.REFRESH)).isTrue();
    }

    @Test
    void validateToken_rejectsAccessPresentedAsRefresh() {
        // Symmetric guard for /refresh: an access token may not stand in for a
        // refresh token, even with a valid signature.
        String access = provider.generateAccessToken(user);
        assertThat(provider.validateToken(access, TokenType.REFRESH)).isFalse();
        assertThat(provider.validateToken(access, TokenType.ACCESS)).isTrue();
        assertThat(provider.validateToken(access)).isTrue();
    }

    @Test
    void validateToken_rejectsTamperedSignature() {
        String access = provider.generateAccessToken(user);
        String tampered = access.substring(0, access.length() - 2) + "AA";
        assertThat(provider.validateToken(tampered)).isFalse();
        assertThat(provider.validateToken(tampered, TokenType.ACCESS)).isFalse();
    }

    @Test
    void getUserIdFromToken_roundTripsThroughBothTypes() {
        String access = provider.generateAccessToken(user);
        JwtProvider.IssuedRefreshToken refresh = provider.generateRefreshToken(user);

        assertThat(provider.getUserIdFromToken(access)).isEqualTo(user.getId());
        assertThat(provider.getUserIdFromToken(refresh.token())).isEqualTo(user.getId());
    }

    @Test
    void downloadToken_carriesTypAndJti() {
        String token = provider.generateDownloadToken(user);

        Claims claims = parse(token);
        assertThat(claims.get(JwtProvider.CLAIM_TYP, String.class)).isEqualTo("DOWNLOAD");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
    }

    @Test
    void validateToken_rejectsDownloadPresentedAsAccess() {
        // Strict typ-claim namespacing is the security boundary — a leaked
        // download token must not be usable as an access token.
        String download = provider.generateDownloadToken(user);
        assertThat(provider.validateToken(download, TokenType.ACCESS)).isFalse();
        assertThat(provider.validateToken(download, TokenType.REFRESH)).isFalse();
        assertThat(provider.validateToken(download, TokenType.DOWNLOAD)).isTrue();
    }

    @Test
    void validateToken_rejectsAccessPresentedAsDownload() {
        String access = provider.generateAccessToken(user);
        assertThat(provider.validateToken(access, TokenType.DOWNLOAD)).isFalse();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
