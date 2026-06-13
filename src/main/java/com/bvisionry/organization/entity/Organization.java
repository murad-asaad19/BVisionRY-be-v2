package com.bvisionry.organization.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.SubscriptionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class Organization extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", nullable = false)
    private SubscriptionTier subscriptionTier = SubscriptionTier.FREE;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    /**
     * True iff this org currently has an active Premium trial.
     * Defensive against a manual tier-downgrade leaving a future trial_ends_at:
     * "on trial" requires both PREMIUM tier and a future expiry.
     */
    public boolean isOnTrial() {
        return subscriptionTier == SubscriptionTier.PREMIUM
            && trialEndsAt != null
            && trialEndsAt.isAfter(Instant.now());
    }

    /** True if the org has ever had a trial (active or historical). */
    public boolean hadTrial() {
        return trialEndsAt != null;
    }
}
