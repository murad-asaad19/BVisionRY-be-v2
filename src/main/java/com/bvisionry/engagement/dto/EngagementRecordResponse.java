package com.bvisionry.engagement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Engagement Record for one founder (spec §4): per cohort, the
 * participation score computed ON READ against the LIVE Scoring &amp; Labels
 * config (the §7 snapshot rule applies only when a score is stamped into a
 * stored object — nothing is stored here), plus the per-session attendance
 * history (§7b stamped).
 *
 * <p><strong>Visibility (§4 DECIDED):</strong> super admin, org admin and the
 * founder's assigned coach only. There is NO member-facing endpoint.
 */
public record EngagementRecordResponse(List<CohortEngagement> cohorts) {

    public record CohortEngagement(
            UUID cohortId,
            String cohortName,
            ParticipationDto participation,
            List<SessionHistoryItem> sessions) {
    }

    /**
     * The weighted score over the config categories. Weights are RENORMALIZED
     * over the categories that have a denominator — a cohort that held no
     * group-coaching sessions must not cost everyone that category's weight.
     * {@code score} is null when NO category has a denominator yet.
     */
    public record ParticipationDto(
            BigDecimal score,
            String bandKey,
            String bandLabel,
            List<CategoryScore> categories,
            Instant computedAt) {
    }

    /**
     * One category row: attended/held (manual) or submitted/assigned
     * (computed), the configured weight, and — when the category has a
     * denominator — the renormalized effective weight and the points it
     * contributed to the score.
     */
    public record CategoryScore(
            String key,
            String label,
            int weight,
            boolean computed,
            int done,
            int total,
            BigDecimal pct,
            BigDecimal effectiveWeight,
            BigDecimal points) {
    }

    /**
     * Every founder in one cohort with their participation score, for the
     * cohort board's Pulse tab (spec §4 names Pulse as a participation
     * surface). Same {@link ParticipationDto} the founder profile renders, so
     * the two surfaces can never quote different numbers.
     *
     * <p>{@code average} is the mean of the members who HAVE a score — a
     * founder with no denominator in any category is excluded rather than
     * counted as zero, matching the null-not-zero rule inside the score.
     */
    public record CohortParticipationResponse(
            List<MemberParticipation> members,
            BigDecimal average) {
    }

    /** One roster row's score for the Pulse column. */
    public record MemberParticipation(
            UUID memberId,
            String memberName,
            ParticipationDto participation) {
    }

    /** A held session with this founder's §7b-stamped attendance. */
    public record SessionHistoryItem(
            UUID sessionId,
            String type,
            String title,
            Instant sessionDate,
            boolean attended,
            Instant markedAt) {
    }
}
