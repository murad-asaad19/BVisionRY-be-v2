package com.bvisionry.pipeline.repository;

import com.bvisionry.pipeline.entity.AutoEnrolment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The auto-enrolment ledger: what the engine decided, per (founder, course,
 * evaluation).
 *
 * <p>ponytail: one query, the only one the engine needs. The founder-facing read
 * ("recommended because of &lt;pillar&gt;") belongs to {@code dashboard_recommendations}
 * and arrives with the surface that consumes it — the {@code (user_id, ...)} unique
 * index in V151 already serves it.
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
}
