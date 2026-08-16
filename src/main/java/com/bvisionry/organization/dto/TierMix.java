package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.SubscriptionTier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The platform's commercial mix, for the SUPER_ADMIN organizations console.
 *
 * <h2>Why this is a map and no longer {@code (premium, trial, free)}</h2>
 * It used to be exactly that, and it outlived the tier model. V156 split paying
 * into Starter / Growth / Founder Success and DELETED the {@code PREMIUM}
 * constant so the compiler would flag every {@code == PREMIUM} site — but
 * {@code DashboardService} never named the constant. It derived the paid count
 * arithmetically ({@code totalOrgs - freeTotal}), so there was no comparison to
 * flag, and the panel kept printing "Premium" long after no org could be one.
 * A super admin read a commercial breakdown in names that mapped to no sellable
 * plan, on the one screen meant to show what the platform is actually selling —
 * while the tier FILTER inches above it already listed all four correctly.
 *
 * <p>Keyed by tier so the shape cannot drift from {@link SubscriptionTier}
 * again: a tier added later flows through from the GROUP BY without touching
 * this record, and one removed simply stops appearing.
 *
 * @param byTier   count of ROOT orgs per tier, in {@link SubscriptionTier}
 *                 declaration order (cheapest first). Tiers with no orgs are
 *                 present with {@code 0} rather than absent, so the console can
 *                 render a stable set of rows.
 * @param onTrial  orgs on an active trial. Deliberately NOT a tier: a trial is a
 *                 STATUS an org holds *while* on some tier, and the old panel
 *                 listing it beside tiers is what made "Premium 4 / Trial 0"
 *                 read as a partition when it never was one.
 */
public record TierMix(Map<SubscriptionTier, Long> byTier, long onTrial) {

    /**
     * Build from raw {@code [tier, count]} rows, filling every tier the enum
     * declares so absent rows read as 0 rather than as a missing line.
     */
    public static TierMix from(Iterable<Object[]> groupedRows, long onTrial) {
        Map<SubscriptionTier, Long> counts = new LinkedHashMap<>();
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            counts.put(tier, 0L);
        }
        for (Object[] row : groupedRows) {
            if (row.length < 2 || row[0] == null) {
                continue;
            }
            counts.merge((SubscriptionTier) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return new TierMix(counts, onTrial);
    }

    /** Total root orgs across every tier — the denominator the console shows. */
    public long total() {
        return byTier.values().stream().mapToLong(Long::longValue).sum();
    }
}
