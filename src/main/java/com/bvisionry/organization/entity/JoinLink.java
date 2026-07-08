package com.bvisionry.organization.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "join_links")
@Getter
@Setter
@NoArgsConstructor
public class JoinLink extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID token = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * When set, this link belongs to a workshop: accepting it also assigns the
     * new member to a team of that workshop (via {@code WorkshopEvents.JoinedViaLink}).
     * Soft UUID reference — the workshops slice owns the table.
     */
    @Column(name = "workshop_id")
    private UUID workshopId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return active && !isExpired();
    }
}
