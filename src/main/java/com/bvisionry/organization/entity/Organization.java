package com.bvisionry.organization.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.SubscriptionTier;
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
     * Parent organization when this org is a sub-organization; null for root
     * orgs. The hierarchy is ONE level deep — the service layer rejects
     * creating a sub-org under another sub-org.
     *
     * <p>LAZY + OSIV is off ({@code spring.jpa.open-in-view=false}), so any
     * access to the parent (including {@link #effectiveSubscriptionTier()})
     * must happen inside a transaction or on an instance loaded with the
     * parent fetched (see {@code OrganizationRepository.findWithParentById}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_organization_id")
    private Organization parentOrganization;

    /** True iff this org is a sub-organization (has a parent). */
    public boolean isSubOrganization() {
        return parentOrganization != null;
    }

    /**
     * The tier that governs feature access: sub-orgs inherit the parent's
     * plan (they have no billing identity of their own — tier and trials are
     * managed on the parent), root orgs use their own.
     */
    public SubscriptionTier effectiveSubscriptionTier() {
        return parentOrganization != null
                ? parentOrganization.getSubscriptionTier()
                : subscriptionTier;
    }

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
