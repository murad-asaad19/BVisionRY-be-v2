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
     * Days without progress on an active course enrolment before a founder is
     * nudged (roadmap §7 items 7 + 18). {@code 0} disables nudging for the org;
     * the DB default is 14 (policy {@code defaults.inactivity_threshold_days})
     * and V149 bounds it to 0..90 — see that migration for why 90 is the cap.
     *
     * <p>Read by {@code InactivityNudgeJob}'s org-scoped query, not by Java:
     * the job never imports this entity (feature boundary), it reads the column.
     */
    @Column(name = "inactivity_nudge_days", nullable = false)
    private int inactivityNudgeDays = 14;

    /**
     * The org's single white-label brand colour as lower-case {@code #rrggbb},
     * or null for "no branding — render the stock theme". Every derived token
     * (button label colour, dark-mode variant, focus ring) is computed from
     * this ONE value by WCAG relative luminance in the web app, so an admin
     * cannot compose an unreadable palette.
     *
     * <p>V154's CHECK constrains the stored shape; the value is interpolated
     * into an SSR {@code <style>} block, so the six-hex-digit guarantee has to
     * hold of the DATA and not merely of the code path that last wrote it.
     */
    @Column(name = "brand_color", length = 7)
    private String brandColor;

    /**
     * {@code minio://bucket/objectKey} marker for the org's logo, or null.
     * Always under {@code org/<this org's id>/branding/} — see
     * {@code OrganizationBrandingService} and V154's CHECK for why that prefix
     * is the tenant boundary and not a naming convention.
     */
    @Column(name = "brand_logo_marker", length = 512)
    private String brandLogoMarker;

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
