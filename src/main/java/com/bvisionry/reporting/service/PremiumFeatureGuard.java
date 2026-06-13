package com.bvisionry.reporting.service;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.PremiumRequiredException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PremiumFeatureGuard {

    private final OrganizationRepository organizationRepository;

    /**
     * Checks if the organization has a Premium subscription.
     * Super Admin bypasses this check entirely.
     * Throws PremiumRequiredException (403) if the org is on the Free tier.
     */
    public void checkPremium(UUID orgId, String feature) {
        if (isSuperAdmin()) return;
        if (!isPremium(orgId)) {
            throw new PremiumRequiredException(feature);
        }
    }

    /**
     * Returns true if the organization has a Premium subscription.
     */
    public boolean isPremium(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
        return org.getSubscriptionTier() == SubscriptionTier.PREMIUM;
    }

    /**
     * Returns true if premium features should be available.
     * Super Admin always gets premium features regardless of org tier.
     */
    public boolean isPremiumOrSuperAdmin(UUID orgId) {
        return isSuperAdmin() || isPremium(orgId);
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getRole() == UserRole.SUPER_ADMIN;
        }
        return false;
    }
}
