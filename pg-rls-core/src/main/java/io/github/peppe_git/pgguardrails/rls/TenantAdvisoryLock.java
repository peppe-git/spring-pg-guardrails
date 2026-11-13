// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A per-tenant mutex held until the end of the current transaction, for check-then-act sequences that no
 * constraint can protect ("count the rows, and insert one more if under the cap"). Under {@code READ COMMITTED}
 * two concurrent transactions of the same tenant read the same count and both insert.
 *
 * <p>The lock is {@code pg_advisory_xact_lock(hashtextextended('namespace:tenant', 0))}. The <b>namespace</b>
 * keeps unrelated rules on the same tenant from waiting on each other. It is released by commit or rollback,
 * never by hand, and therefore requires an open transaction: outside one it would protect nothing, so that is an
 * error rather than a silent no-op.
 */
public class TenantAdvisoryLock {

    private final JdbcTemplate jdbc;

    public TenantAdvisoryLock(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** Blocks until the {@code (namespace, tenant)} lock is held; released at the end of the transaction. */
    public void lock(String namespace, Object tenantId) {
        String key = key(namespace, tenantId);
        run("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))", key);
    }

    /** Takes the lock if it is free and returns whether it was taken; never waits. */
    public boolean tryLock(String namespace, Object tenantId) {
        String key = key(namespace, tenantId);
        return run("select pg_try_advisory_xact_lock(hashtextextended(?, 0))", key);
    }

    private boolean run(String sql, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RlsUsageException("TenantAdvisoryLock needs an open Spring transaction: a transaction-scoped "
                    + "lock taken outside one is released by the same statement and protects nothing");
        }
        Boolean taken = jdbc.execute((ConnectionCallback<Boolean>) con -> {
            if (con.getAutoCommit()) {
                throw new RlsUsageException("refusing to take a transaction-scoped lock on an auto-commit "
                        + "connection: it would be released immediately");
            }
            try (var ps = con.prepareStatement(sql)) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    Object value = rs.getObject(1);
                    return !(value instanceof Boolean b) || b;
                }
            }
        });
        return Boolean.TRUE.equals(taken);
    }

    private static String key(String namespace, Object tenantId) {
        if (namespace == null || namespace.isBlank() || namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("namespace must be non-blank and must not contain ':'");
        }
        return namespace + ":" + TenantContext.toGucValue(tenantId);
    }
}
