package com.bvisionry.auth.jwt;

import com.bvisionry.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtProvider {

    static final String CLAIM_TYP = "typ";

    /** HS256 requires the signing key to be at least 256 bits (32 bytes). */
    private static final int MIN_HS256_KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final byte[] secretBytes;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final long downloadTokenExpirationMs;
    private final String issuer;
    private final String audience;

    public JwtProvider(
            @Value("${bvisionry.jwt.secret}") String secret,
            @Value("${bvisionry.jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${bvisionry.jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
            @Value("${bvisionry.jwt.download-token-expiration-ms:60000}") long downloadTokenExpirationMs,
            @Value("${bvisionry.jwt.issuer:bvisionry-api}") String issuer,
            @Value("${bvisionry.jwt.audience:bvisionry-app}") String audience) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.downloadTokenExpirationMs = downloadTokenExpirationMs;
        this.issuer = issuer;
        this.audience = audience;
    }

    @PostConstruct
    void validateConfig() {
        if (secretBytes.length < MIN_HS256_KEY_BYTES) {
            throw new IllegalStateException(
                    "bvisionry.jwt.secret must be at least " + MIN_HS256_KEY_BYTES
                            + " bytes for HS256 (got " + secretBytes.length + ")");
        }
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);
        UUID jti = UUID.randomUUID();

        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .id(jti.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .claim(CLAIM_TYP, TokenType.ACCESS.name())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey);

        if (user.getOrganization() != null) {
            builder.claim("orgId", user.getOrganization().getId().toString());
        }

        return builder.compact();
    }

    public IssuedRefreshToken generateRefreshToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpirationMs);
        UUID jti = UUID.randomUUID();

        String token = Jwts.builder()
                .subject(user.getId().toString())
                .id(jti.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .claim(CLAIM_TYP, TokenType.REFRESH.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();

        return new IssuedRefreshToken(token, jti, now.toInstant(), expiry.toInstant());
    }

    /**
     * Mints a short-lived (~60 s) token used to authenticate direct browser →
     * Railway calls for binary endpoints (PDF, XLSX). The {@code typ} claim is
     * {@link TokenType#DOWNLOAD} so it cannot be replayed as an access or
     * refresh token.
     */
    public String generateDownloadToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + downloadTokenExpirationMs);
        UUID jti = UUID.randomUUID();

        return Jwts.builder()
                .subject(user.getId().toString())
                .id(jti.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .claim(CLAIM_TYP, TokenType.DOWNLOAD.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public long getDownloadTokenExpirationMs() {
        return downloadTokenExpirationMs;
    }

    /**
     * Parses, signature-verifies, and validates type/audience/issuer in a single pass.
     * Returns the {@link Claims} on success so callers can read subject/jti without
     * re-parsing — avoids paying HMAC verification twice on every authenticated request.
     */
    public Optional<Claims> parseAndValidate(String token, TokenType expected) {
        try {
            Claims claims = parse(token);
            String typ = claims.get(CLAIM_TYP, String.class);
            if (!expected.name().equals(typ)) {
                return Optional.empty();
            }
            if (claims.getAudience() == null
                    || !claims.getAudience().contains(audience)
                    || !Objects.equals(issuer, claims.getIssuer())) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean validateToken(String token, TokenType expected) {
        return parseAndValidate(token, expected).isPresent();
    }

    public boolean validateToken(String token) {
        return validateToken(token, TokenType.ACCESS);
    }

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(parse(token).getSubject());
    }

    public UUID getJti(String token) {
        String id = parse(token).getId();
        if (id == null) {
            throw new JwtException("Token is missing jti claim");
        }
        return UUID.fromString(id);
    }

    public static UUID jtiOf(Claims claims) {
        String id = claims.getId();
        if (id == null) {
            throw new JwtException("Token is missing jti claim");
        }
        return UUID.fromString(id);
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record IssuedRefreshToken(String token, UUID jti, Instant issuedAt, Instant expiresAt) {}
}
