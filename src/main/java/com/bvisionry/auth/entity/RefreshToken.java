package com.bvisionry.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of an issued refresh JWT, keyed by the JWT's {@code jti}
 * claim. Used to enforce single-use rotation, revocation on logout/password
 * change, and theft detection (replay of an already-revoked token revokes all
 * of the user's refresh tokens).
 *
 * <p>Not modeled as a {@code BaseEntity} subclass: {@code BaseEntity} carries
 * {@code updated_at} which is meaningless here — a refresh-token row is
 * append-once-then-revoke, never edited.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** The {@code jti} claim of the issued refresh JWT — the lookup key. */
    @Column(nullable = false, unique = true)
    private UUID jti;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null once the token has been rotated, logged out, or revoked. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** When this row was rotated, the {@code jti} of the replacement token. */
    @Column(name = "replaced_by_jti")
    private UUID replacedByJti;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
