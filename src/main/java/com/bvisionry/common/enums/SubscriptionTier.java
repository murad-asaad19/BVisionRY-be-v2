package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDate;

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
 * <p><strong>The cohort meter is a LAUNCH quota over CALENDAR periods</strong>
 * (redesign spec §8, V167): drafts are free, launching consumes quota, and the
 * UI can always say "next launch available Oct 1". This replaced the previous
 * creation-time rolling window — a rate on creation punished drafting, and a
 * rolling window could never print a reset date.
 *
 * <p>The ordinal order is the capacity order (cheapest first). Nothing persists
 * the ordinal ({@code @Enumerated(EnumType.STRING)} everywhere), so it is free
 * to read as a ranking.
 */
public enum SubscriptionTier {

    /**
     * Not a sold plan: pre-purchase / lapsed-trial state. May DRAFT cohorts but
     * never launch one (spec §8: launching is the paid act; the old model let
     * FREE create one cohort per quarter, which under launch semantics would
     * hand out a free measured cohort — deliberately closed to 0).
     */
    FREE("Free", new LaunchQuota(0, LaunchPeriodUnit.QUARTER)),

    /** $299/mo — 1 cohort launch per calendar quarter. */
    STARTER("Starter", new LaunchQuota(1, LaunchPeriodUnit.QUARTER)),

    /** $599/mo — 1 cohort launch per calendar month. Also where V156 lands every ex-PREMIUM org. */
    GROWTH("Growth", new LaunchQuota(1, LaunchPeriodUnit.MONTH)),

    /** Contact Sales — unlimited launches, and the only tier with learning content. */
    FOUNDER_SUCCESS("Founder Success", null);

    /** The calendar grain a tier's launch allowance resets on. */
    public enum LaunchPeriodUnit {
        MONTH("month"),
        QUARTER("quarter");

        private final String label;

        LaunchPeriodUnit(String label) {
            this.label = label;
        }

        /** "month" / "quarter" — the human half of a refusal or usage message. */
        public String label() {
            return label;
        }

        /** First day of the calendar period containing {@code date}. */
        public LocalDate periodStart(LocalDate date) {
            return this == MONTH
                    ? date.withDayOfMonth(1)
                    : date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
        }

        /** First day of the next calendar period — "resets Oct 1". */
        public LocalDate nextPeriodStart(LocalDate date) {
            return this == MONTH
                    ? periodStart(date).plusMonths(1)
                    : periodStart(date).plusMonths(3);
        }
    }

    /**
     * How many cohorts an org may LAUNCH per calendar period. A quota on the
     * launch transition and not on creation: drafts cost nothing.
     *
     * @param perPeriod launches allowed per calendar period (0 = none)
     * @param unit      the calendar grain the allowance resets on
     */
    public record LaunchQuota(int perPeriod, LaunchPeriodUnit unit) {

        /** "1 launch per quarter" — the ceiling half of a refusal message. */
        public String describe() {
            return perPeriod + (perPeriod == 1 ? " cohort launch per " : " cohort launches per ")
                    + unit.label();
        }
    }

    private final String label;
    private final LaunchQuota launchQuota;

    SubscriptionTier(String label, LaunchQuota launchQuota) {
        this.label = label;
        this.launchQuota = launchQuota;
    }

    /** Human-facing plan name, as printed on the pricing page. */
    public String label() {
        return label;
    }

    /** The org's cohort-launch quota, or {@code null} when unlimited. */
    public LaunchQuota launchQuota() {
        return launchQuota;
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
