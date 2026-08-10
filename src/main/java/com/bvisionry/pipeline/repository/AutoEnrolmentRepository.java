package com.bvisionry.pipeline.repository;

import com.bvisionry.pipeline.entity.AutoEnrolment;
import com.bvisionry.pipeline.entity.AutoEnrolmentOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The auto-enrolment ledger: what the engine decided, per (founder, course,
 * evaluation).
 *
 * <p>Two queries: the engine's idempotency read, and the founder's own read of the
 * decisions made about them. Both are keyed on {@code user_id} first and are served
 * by {@code uq_auto_enrolments} (V151); neither needed a new index.
 */
@Repository
public interface AutoEnrolmentRepository extends JpaRepository<AutoEnrolment, UUID> {

    /**
     * Everything already decided for this founder on this evaluation. This is the
     * idempotency read: a course present here has been handled, whatever the outcome
     * was, and is not reconsidered. The unique index is the backstop for two workers
     * racing the same submission.
     *
     * <p>Keyed on BOTH ids even though {@code submissions.user_id} makes the second
     * one redundant today. The idempotency key is
     * {@code (user_id, course_id, submission_id)}; a query that omits {@code user_id}
     * is only equivalent because of an invariant enforced three slices away, and a
     * read that does not state the key it is checking is one schema change from
     * being silently wrong.
     */
    List<AutoEnrolment> findBySubmissionIdAndUserId(UUID submissionId, UUID userId);

    /**
     * One founder's own decisions, newest first — the read behind "recommended
     * because of &lt;pillar&gt;".
     *
     * <p><strong>The founder id is a parameter, and its only legitimate argument is
     * the AUTHENTICATED caller's own id</strong> (resolved server-side from
     * {@code CurrentUserAccessor}, never from a path or a body). There is
     * deliberately no all-founders overload: this ledger records what an assessment
     * concluded about a named person, which is theirs.
     *
     * <p>{@code outcome} is a parameter rather than a hard-coded {@code ENROLLED}
     * because the same query answers the operator's "why wasn't X enrolled" —
     * but the founder surface passes {@code ENROLLED} and only {@code ENROLLED}:
     * {@code ALREADY_ENROLLED} is a course they already had, and
     * {@code COURSE_NOT_PUBLISHED} enrolled nobody.
     *
     * <p>Ordered by id after {@code createdAt} because a single evaluation writes
     * its rows in one loop and {@code Instant.now()} can repeat within it; without
     * the tie-break the founder's list would be free to reshuffle between requests.
     */
    List<AutoEnrolment> findByUserIdAndOutcomeOrderByCreatedAtDescIdAsc(
            UUID userId, AutoEnrolmentOutcome outcome);

    /**
     * The founder's OPEN suggestions (spec §3 Suggest mode): decided, not yet
     * taken up. {@code accepted_at IS NULL} is the whole predicate — accepting
     * stamps it (§7b) and the row leaves this list without losing its history.
     */
    List<AutoEnrolment> findByUserIdAndOutcomeAndAcceptedAtIsNullOrderByCreatedAtDescIdAsc(
            UUID userId, AutoEnrolmentOutcome outcome);
}
