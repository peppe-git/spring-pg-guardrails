// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.util.Objects;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Writes the row-level security settings at the start of every new Spring transaction: the strict-mode flag (when
 * {@link RlsSettings#strict()}) and the tenant bound by {@link TenantContext} (when
 * {@link RlsSettings#applyOnTransactionBegin()}).
 *
 * <p>Register it on the transaction manager of the PostgreSQL {@code DataSource}
 * ({@code transactionManager.addListener(new RlsTransactionListener(tenantGuc))}); the Spring Boot
 * auto-configuration does it for the primary {@code DataSource}. It needs Spring Framework 6.1 or later.
 *
 * <p>Nested (savepoint) and participating transactions are skipped: the outer transaction already carries the
 * settings.
 */
public final class RlsTransactionListener implements TransactionExecutionListener {

    private final TenantGuc tenantGuc;

    public RlsTransactionListener(TenantGuc tenantGuc) {
        this.tenantGuc = Objects.requireNonNull(tenantGuc, "tenantGuc");
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null || !transaction.isNewTransaction() || transaction.isNested()) {
            return;
        }
        tenantGuc.onTransactionBegin();
    }

    TenantGuc tenantGuc() {
        return tenantGuc;
    }
}
