package com.bvisionry.programflow.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.bvisionry.common.enums.SubscriptionTier;

/**
 * The plan-usage card for the cohorts surface (spec §8: "1 of 1 launched ·
 * resets Oct 1") plus the super-admin extras. Always describes the BILLING
 * FAMILY (root org), whichever family org it was requested for.
 *
 * @param tier              the family's plan (the root org's own tier)
 * @param trial             an active trial governs: 1 launch TOTAL, lifetime
 * @param periodLabel       "month" / "quarter" / "trial" / "unlimited"
 * @param allowance         launches allowed in the current period incl. grants;
 *                          null = unlimited
 * @param used              launches consumed (period for metered tiers,
 *                          lifetime for trial/unlimited)
 * @param remaining         allowance − used, floored at 0; null = unlimited
 * @param grantsThisPeriod  super-admin grant rows counted into {@code allowance}
 * @param nextResetDate     first day of the next calendar period; null when no
 *                          period applies (trial / unlimited)
 * @param nextAvailableDate when exhausted: the first day a launch works again;
 *                          null while quota remains or when no date will help
 *                          (trial cap, FREE)
 * @param stalledOnboarding org family older than the platform
 *                          {@code attention.onboarding_stalled_hours} threshold
 *                          with zero launches ever (§11 stalled-onboarding
 *                          indicator)
 * @param orgCreatedAt      when the root org was created (the "stalled since")
 */
public record CohortPlanUsageResponse(
        SubscriptionTier tier,
        boolean trial,
        String periodLabel,
        Integer allowance,
        long used,
        Integer remaining,
        int grantsThisPeriod,
        LocalDate nextResetDate,
        LocalDate nextAvailableDate,
        boolean stalledOnboarding,
        Instant orgCreatedAt) {
}
