// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Marks the current Spring transaction as tracked by a {@link TenantGuc}, so that {@link TenantContext} scopes
 * opened inside it can switch (and restore) the transaction-local GUC.
 *
 * <p>Bound as a transaction resource; unbound on completion, and suspended/resumed together with the transaction
 * (a {@code REQUIRES_NEW} transaction gets its own state).
 */
final class RlsTransactionState {

    private static final Object KEY = new Object() {
        @Override
        public String toString() {
            return "pg-rls transaction state";
        }
    };

    private final TenantGuc guc;

    private RlsTransactionState(TenantGuc guc) {
        this.guc = guc;
    }

    /** The state of the current transaction, or {@code null} when no tracked transaction is open. */
    static RlsTransactionState current() {
        return (RlsTransactionState) TransactionSynchronizationManager.getResource(KEY);
    }

    /** Tracks the current transaction with {@code guc}; a no-op when it is already tracked. */
    static void bind(TenantGuc guc) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(KEY)) {
            return;
        }
        RlsTransactionState state = new RlsTransactionState(guc);
        TransactionSynchronizationManager.bindResource(KEY, state);
        TransactionSynchronizationManager.registerSynchronization(new Unbinder(state));
    }

    boolean isCurrent() {
        return current() == this;
    }

    /** Sets the tenant GUC of the current transaction and returns its previous value. */
    String swapTenant(String gucValue) {
        return guc.swapTenantGuc(gucValue);
    }

    private static final class Unbinder implements TransactionSynchronization {

        private final RlsTransactionState state;

        Unbinder(RlsTransactionState state) {
            this.state = state;
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(KEY, state);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
        }
    }
}
