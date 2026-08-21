package com.bvisionry.common.participation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The cohort's participation picture reduced to what a cross-feature consumer
 * may know: the average score and how many members land in each band. Lives in
 * the shared kernel (like {@code MediaUrlPort}) because the cohort growth
 * report needs a participation sheet and the ArchUnit ratchet forbids a
 * comparison→engagement import. Implemented by the engagement slice, which owns
 * the formula.
 */
public interface ParticipationBandsPort {

    /**
     * Band member-counts for one cohort, cut to the org's tenant slice — the
     * same read the Engagement tab renders, so the two can never disagree.
     */
    CohortBands cohortBands(UUID orgId, UUID cohortId);

    /**
     * {@code unscored} counts members with no denominator in any category yet —
     * they carry no band and must not be folded into one (null-not-zero rule).
     */
    record CohortBands(BigDecimal average, List<BandCount> bands, int unscored) {
    }

    /** One band's member count; ordered by band key (band_1, band_2, …). */
    record BandCount(String bandKey, String bandLabel, int members) {
    }
}
