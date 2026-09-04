package com.bvisionry.coaching.web;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.bvisionry.coaching.repository.SessionMaterializer;
import com.bvisionry.common.event.ProgramFlowEvents;

import lombok.extern.slf4j.Slf4j;

/**
 * Re-materialises a cohort's session rows after the transaction that changed
 * its board or its roster commits (spec §4).
 *
 * <p>AFTER_COMMIT for the reason every listener in this codebase is: a
 * rolled-back launch or enrolment must not leave phantom session rows, and the
 * roster the materialiser reads only reads correctly once committed.
 *
 * <p>WHY THE TRANSACTION IS EXPLICIT. In an AFTER_COMMIT callback the original
 * transaction's resources are still bound, so a plain {@code @Transactional}
 * call would silently JOIN a transaction that has already committed and lose
 * every write. A new one is therefore started by hand — and starting it here,
 * rather than annotating {@link SessionMaterializer#sync}, keeps the catch
 * OUTSIDE it: swallowing an exception inside a transaction only converts the
 * failure into an {@code UnexpectedRollbackException} at commit.
 *
 * <p>A failure is logged, not rethrown. The publisher's work (the launch, the
 * enrolment, the board save) is already committed and must not be reported as
 * failed because a derived row could not be written; {@code sync} is
 * idempotent, so the next roster or board change repairs it.
 */
@Component
@Slf4j
public class CohortSessionsChangedHandler {

    private final SessionMaterializer materializer;
    private final TransactionTemplate ownTransaction;

    public CohortSessionsChangedHandler(SessionMaterializer materializer,
                                        PlatformTransactionManager transactionManager) {
        this.materializer = materializer;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCohortSessionsChanged(ProgramFlowEvents.CohortSessionsChanged event) {
        try {
            ownTransaction.executeWithoutResult(status -> materializer.sync(event.cohortId()));
        } catch (RuntimeException e) {
            log.error("Session materialisation failed for cohort {} — the next board or roster "
                    + "change will retry it", event.cohortId(), e);
        }
    }
}
