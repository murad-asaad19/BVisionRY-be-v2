package com.bvisionry.common.tx;

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
}
