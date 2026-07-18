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
 * Single-use token backing the "forgot your password" flow, mirroring the
 * {@link com.bvisionry.organization.entity.Invitation} token pattern: a random
 * UUID emailed as a link, valid for a short window, spent on first successful
 * use ({@code usedAt} set).
 *
 * <p>Not a {@code BaseEntity} subclass for the same reason as
 * {@link RefreshToken}: rows are append-once-then-spend, so {@code updated_at}
 * would be meaningless.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** The opaque value embedded in the emailed reset link — the lookup key. */
    @Column(nullable = false, unique = true)
    private UUID token = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null once the token (or any sibling for the same user) was spent. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isUsable() {
        return usedAt == null && Instant.now().isBefore(expiresAt);
    }
}
