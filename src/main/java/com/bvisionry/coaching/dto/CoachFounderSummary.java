package com.bvisionry.coaching.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.bvisionry.coaching.repository.CoachingReadRepository.RosterRow;

/**
 * One founder on the coach's roster — a triage row (redesign spec §2.2): name
 * (no email — {@code coach_sees} does not include contact data), the GRANTED
 * cohorts they belong to, grant-scoped per-type program completion, the latest
 * evaluated FRI plus the computed comparison's Δ (the founder-profile rule —
 * the row links to that profile, so both must read the same movement), the
 * lowest incomplete module, the open-items split and the founder's last
 * activity.
 * {@code completionPct} is null when nothing is assigned — "no tasks yet" is
 * different from 0%.
 *
 * <p>{@code unreadReplies} is an honest proxy — there is no read tracking:
 * OPEN comment threads on the founder's exercise submissions whose last
 * message was authored by the founder (the ball is in the coach's court).
 */
public record CoachFounderSummary(
        UUID id,
        String name,
        String cohortNames,
        int totalTasks,
        int submittedTasks,
        Integer completionPct,
        /** Latest evaluated overall score, null when never evaluated. */
        BigDecimal friLatest,
        /**
         * The founder's computed distance comparison
         * ({@code founder_comparisons.overall_delta}) for their anchor cohort —
         * the same number the founder profile this row opens shows. Null when
         * no cohort designates a pair or the comparison has not been computed.
         */
        BigDecimal friDelta,
        /** Lowest incomplete module in the granted cohorts, null when everything is done. */
        String currentModule,
        /** SUBMITTED exercise submissions waiting on a review. */
        int awaitingReview,
        /** OPEN threads whose last message is the founder's (unread-replies proxy). */
        int unreadReplies,
        /** §7b: the founder's own most recent action (login/save/submit/enroll). */
        Instant lastActivityAt,
        /** Whole days since {@code lastActivityAt}; null when there is no activity at all. */
        Integer idleDays) {

    public static CoachFounderSummary from(RosterRow row) {
        OffsetDateTime lastActivity = row.lastActivityAt();
        return new CoachFounderSummary(row.id(), row.name(), row.cohortNames(),
                row.totalTasks(), row.submittedTasks(),
                row.totalTasks() == 0 ? null
                        : Math.round(100f * row.submittedTasks() / row.totalTasks()),
                row.friLatest(), row.friDelta(), row.currentModule(),
                row.awaitingReview(), row.unreadReplies(),
                lastActivity == null ? null : lastActivity.toInstant(),
                lastActivity == null ? null
                        : (int) ChronoUnit.DAYS.between(lastActivity.toInstant(), Instant.now()));
    }
}
