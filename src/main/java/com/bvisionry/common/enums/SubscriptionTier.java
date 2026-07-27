package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Period;

/**
 * The plans Bvisionry actually sells, per the pricing page
 * ({@code web/src/lib/founder-content.ts}, {@code FRI_PRICING_TIERS} — the
 * source of truth for the names and the ceilings quoted below).
 *
 * <p><strong>{@code PREMIUM} is gone; {@link #GROWTH} replaces it.</strong>
 * "Premium" was never a sold plan — it was a placeholder for "this org pays",
 * and every question the codebase asked of it ("is this org premium", "count
 * the premium orgs", "expire the lapsed premium trials") was really the binary
 * {@link #isPaid()} question. Splitting paid into three tiers means that binary
 * has to be spelled out, so the constant was REMOVED rather than left as a
 * value nothing can be on: with it gone the compiler flags every
 * {@code == PREMIUM} site, which is what turned this migration from a silent
 * semantic drift into a mechanical one. V156 maps every stored {@code PREMIUM}
 * row to {@code GROWTH} — the higher self-serve ceiling — so no paying
 * customer loses capacity on deploy.
 *
 * <p>The ordinal order is the capacity order (cheapest first). Nothing persists
 * the ordinal ({@code @Enumerated(EnumType.STRING)} everywhere), so it is free
 * to read as a ranking.
 */
public enum SubscriptionTier {

    /** Not a sold plan: pre-purchase / lapsed-trial state. Metered like Starter. */
    FREE("Free", new CohortRate(1, Period.ofMonths(3), "quarter")),

    /** $299/mo — 1 cohort per quarter. */
    STARTER("Starter", new CohortRate(1, Period.ofMonths(3), "quarter")),

    /** $599/mo — 1 cohort per month. Also what a trial grants, and where V156 lands every ex-PREMIUM org. */
    GROWTH("Growth", new CohortRate(1, Period.ofMonths(1), "month")),

    /** Contact Sales — unlimited cohorts, and the only tier with learning content. */
    FOUNDER_SUCCESS("Founder Success", null);

    /**
     * How fast an org may CREATE cohorts. Deliberately a rate and not a seat
     * count: the unit of value is a measured cohort, and nothing in the product
     * meters founder headcount.
     *
     * @param max         cohorts allowed per window
     * @param window      the rolling window (see {@code CohortService} for why rolling)
     * @param windowLabel how to say {@code window} to a human — "quarter", "month"
     */
    public record CohortRate(int max, Period window, String windowLabel) {

        /** "1 cohort per quarter" — the ceiling half of a refusal message. */
        public String describe() {
            return max + (max == 1 ? " cohort per " : " cohorts per ") + windowLabel;
        }
    }

    private final String label;
    private final CohortRate cohortRate;

    SubscriptionTier(String label, CohortRate cohortRate) {
        this.label = label;
        this.cohortRate = cohortRate;
    }

    /** Human-facing plan name, as printed on the pricing page. */
    public String label() {
        return label;
    }

    /** The org's cohort-creation ceiling, or {@code null} when unlimited. */
    public CohortRate cohortRate() {
        return cohortRate;
    }

    /**
     * True for every tier a customer pays for. This is the predicate the
     * entitlement gate ({@code PremiumFeatureGuard}) runs on, so a Starter org
     * reaches exactly the surfaces a Premium org reached before V156 — the
     * gated surfaces (benchmarks, ROI, insights) are unchanged by the split.
     * Narrowing any of them to a specific tier is a separate, deliberate change.
     */
    public boolean isPaid() {
        return this != FREE;
    }

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static SubscriptionTier fromValue(String value) {
        return SubscriptionTier.valueOf(value.toUpperCase());
    }
}
