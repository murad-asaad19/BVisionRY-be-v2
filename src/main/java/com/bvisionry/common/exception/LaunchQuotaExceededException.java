package com.bvisionry.common.exception;

import java.time.LocalDate;

import com.bvisionry.common.enums.SubscriptionTier;

/**
 * A cohort launch was refused because the billing family's calendar-period
 * quota is spent (redesign spec §8). Maps to 409 with a problem body carrying
 * {@code nextAvailableDate} (first day of the next period; {@code null} when
 * no date will help — trial lifetime cap or a FREE org) and {@code tier}, so
 * the UI can render the disabled Launch button with the date + Request
 * upgrade.
 */
public class LaunchQuotaExceededException extends RuntimeException {

    private final LocalDate nextAvailableDate;
    private final SubscriptionTier tier;

    public LaunchQuotaExceededException(String message, LocalDate nextAvailableDate,
            SubscriptionTier tier) {
        super(message);
        this.nextAvailableDate = nextAvailableDate;
        this.tier = tier;
    }

    public LocalDate getNextAvailableDate() {
        return nextAvailableDate;
    }

    public SubscriptionTier getTier() {
        return tier;
    }
}
