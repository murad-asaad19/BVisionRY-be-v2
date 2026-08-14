package com.bvisionry.programflow.web;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.organization.event.MemberJoinedEvent;
import com.bvisionry.organization.event.MemberMovedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Auto-enroll (spec §13.3): after a member-joined or member-moved transaction
 * commits, enroll the member into every cohort whose assignment to the
 * destination org has {@code autoEnroll} — the cohort mirror of the
 * assessment slice's {@code AutoAssignmentEventHandler}. AFTER_COMMIT for the
 * same reasons: a rolled-back join must not leave phantom enrollments, and a
 * move only reads correctly once committed.
 *
 * <p>The event's {@code userType} is deliberately unused here (unlike the
 * assessment handler, whose rules match on it): who may be enrolled is the
 * learners-only rule, and that lives once inside
 * {@link CohortService#autoEnroll} next to the manual paths that share it.
 */
@Component
@RequiredArgsConstructor
public class CohortAutoEnrollHandler {

    private final CohortService cohortService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberJoined(MemberJoinedEvent event) {
        cohortService.autoEnroll(event.organizationId(), event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberMoved(MemberMovedEvent event) {
        cohortService.autoEnroll(event.toOrganizationId(), event.userId());
    }
}
