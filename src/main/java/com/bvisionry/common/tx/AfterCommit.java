package com.bvisionry.common.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a {@link Runnable} after the surrounding transaction commits, or immediately if no
 * transaction synchronization is active.
 *
 * <p>Deferring writes/cache-evicts to {@code afterCommit} prevents a concurrent reader
 * from re-populating a cache from the still-open transaction's pre-commit view; the
 * fallback path keeps the contract working in unit-test contexts that aren't wrapped in
 * {@code @Transactional}.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Like {@link #run(Runnable)}, but tolerant of asynchronous-executor saturation. If the
     * action triggers a {@link TaskRejectedException} (e.g. an {@code @Async} dispatch whose
     * thread pool is full and uses an aborting rejection policy), it is caught and logged
     * rather than propagating out of {@code afterCommit()} — which, unlike {@code afterCompletion},
     * Spring propagates straight out of {@code commit()} and would otherwise surface as a request
     * error even though the transaction already committed durably.
     *
     * <p>The committed work stays durable; the relevant scheduled reaper re-dispatches the
     * dropped task on its next tick, so this degrades gracefully under load instead of failing
     * a request whose state was already persisted.
     */
    public static void dispatch(Runnable action) {
        run(() -> {
            try {
                action.run();
            } catch (TaskRejectedException ex) {
                log.warn("After-commit async dispatch rejected (executor saturated); "
                        + "leaving the committed work for the scheduled reaper to recover", ex);
            }
        });
    }
}
