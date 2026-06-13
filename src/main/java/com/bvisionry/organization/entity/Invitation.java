package com.bvisionry.organization.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
@Getter
@Setter
@NoArgsConstructor
public class Invitation extends BaseEntity {

    @Column(nullable = false)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, unique = true)
    private UUID token = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "first_viewed_at")
    private Instant firstViewedAt;

    @Column(name = "last_viewed_at")
    private Instant lastViewedAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount = 0;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isAcceptable() {
        return status == InvitationStatus.PENDING && !isExpired();
    }

    public enum InvitationStatus {
        PENDING,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }
}
