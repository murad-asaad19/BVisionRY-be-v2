package com.bvisionry.calendar.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The {@code state} carried across the Google round trip: an HMAC-signed token
 * naming the coach who started the connect, valid for ten minutes.
 *
 * <h2>Why not a state cookie, as {@code OAuth2Controller} uses</h2>
 * The SSO login is started BY THE BROWSER, so a cookie set on that response
 * comes back on the callback. This flow is started by an authenticated
 * {@code POST} the web app makes through its BFF — a server-to-server call whose
 * {@code Set-Cookie} never reaches the coach's browser. The state therefore has
 * to be self-contained, which also means it can carry the identity: the callback
 * arrives with no session at all (Google redirects the bare browser), so
 * "who is connecting" can only come from the signed state.
 *
 * <p>Signed with the app's JWT secret but deliberately carrying no {@code typ},
 * issuer or audience — {@code JwtProvider.parseAndValidate} requires all three,
 * so a state token can never be replayed as an access token.
 */
@Component
class CalendarOAuthState {

    /** Long enough to pick a Google account and read the consent screen, short enough to be useless if leaked. */
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String PURPOSE = "calendar-connect";

    private final SecretKey key;

    CalendarOAuthState(@Value("${bvisionry.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    String sign(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                // A fresh jti per attempt: two connects started from two tabs produce
                // two distinct states, so neither callback can be mistaken for the other.
                .id(UUID.randomUUID().toString())
                .claim(PURPOSE_CLAIM, PURPOSE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key)
                .compact();
    }

    /** Empty when the state is missing, tampered with, expired, or minted for something else. */
    Optional<UUID> verify(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .require(PURPOSE_CLAIM, PURPOSE)
                    .build()
                    .parseSignedClaims(state)
                    .getPayload();
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
