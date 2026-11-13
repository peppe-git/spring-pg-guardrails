// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the row-level security settings of the current transaction.
 *
 * <p>Every value is written with {@code set_config(name, value, true)}: <b>transaction-local</b>, so it disappears
 * at commit or rollback and can never leak to the next borrower of a pooled connection, and with the value as a
 * <b>bound parameter</b>, never concatenated into SQL.
 *
 * <p>Transaction-local has one trap, and this class closes it: on a connection in auto-commit the "transaction" is
 * the {@code set_config} statement itself, so the value dies immediately and the following queries see no tenant
 * (zero rows, no error). Every write therefore requires an open Spring transaction <b>and</b> a connection that is
 * not in auto-commit, and throws {@link RlsUsageException} otherwise.
 *
 * <p>Works with any transaction manager that binds a JDBC connection for the {@link DataSource}:
 * {@code DataSourceTransactionManager}, {@code JdbcTransactionManager}, and {@code JpaTransactionManager} (which
 * exposes the JPA connection to plain JDBC access).
 */
public class TenantGuc {

    private static final Log log = LogFactory.getLog(TenantGuc.class);

    private static final String SWAP_SQL = "with previous as materialized (select current_setting(?, true) as v) "
            + "select v, set_config(?, ?, true) from previous";

    private final JdbcTemplate jdbc;
    private final RlsSettings settings;
    private volatile Boolean postgres;

    public TenantGuc(DataSource dataSource, RlsSettings settings) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public RlsSettings settings() {
        return settings;
    }

    /**
     * Sets the tenant of the current transaction. Low level: prefer {@link TenantContext} scopes and
     * {@link RlsTransactionListener}, or {@link #runAs} to switch inside an open transaction.
     *
     * @throws RlsUsageException outside a transaction or on an auto-commit connection
     */
    public void apply(Object tenantId) {
        String value = TenantContext.toGucValue(tenantId);
        requireTransaction("apply");
        RlsTransactionState.bind(this);
        setConfig(List.of(settings.tenantGuc(), value));
    }

    /** {@link #apply} with the tenant bound by {@link TenantContext}, if any; returns whether one was applied. */
    public boolean applyFromContext() {
        Object tenant = TenantContext.current().orElse(null);
        if (tenant == null) {
            return false;
        }
        apply(tenant);
        return true;
    }

    /**
     * Runs {@code work} as {@code tenantId} inside the current transaction: the GUC and the {@link TenantContext}
     * are switched for the duration and both restored to their previous values afterwards.
     *
     * @throws RlsUsageException outside a transaction
     */
    public <T, X extends Throwable> T runAs(Object tenantId, TenantCallable<T, X> work) throws X {
        TenantContext.toGucValue(tenantId);
        requireTransaction("runAs");
        RlsTransactionState.bind(this);
        return TenantContext.callAs(tenantId, work);
    }

    /**
     * Opens the admin read escape for the rest of the current transaction: the {@code FOR SELECT} policies
     * installed with {@code admin_read => true} let rows of every tenant through. Writes are never widened, and the
     * SQL function also requires the transaction to be {@code READ ONLY}.
     *
     * <p>Call it only after the caller has been authorised as a platform administrator.
     *
     * @throws RlsUsageException outside a transaction, or inside a read-write one
     */
    public void applyAdminRead() {
        requireTransaction("applyAdminRead");
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new RlsUsageException("the admin read escape is only allowed in read-only transactions "
                    + "(@Transactional(readOnly = true)): it widens SELECT policies, and a read-write transaction "
                    + "that reads every tenant is one statement away from writing to the wrong one");
        }
        setConfig(List.of(settings.adminReadGuc(), "on"));
    }

    /** The tenant GUC as the database sees it in the current transaction ({@code null} or empty when unset). */
    public String currentGucValue() {
        requireTransaction("currentGucValue");
        return jdbc.queryForObject("select current_setting(?, true)", String.class, settings.tenantGuc());
    }

    /** Called by {@link RlsTransactionListener} right after a new transaction begins. */
    void onTransactionBegin() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        List<String> pairs = new ArrayList<>(4);
        if (settings.strict()) {
            pairs.add(settings.strictGuc());
            pairs.add("on");
        }
        if (settings.applyOnTransactionBegin()) {
            TenantContext.current().ifPresent(tenant -> {
                pairs.add(settings.tenantGuc());
                pairs.add(TenantContext.toGucValue(tenant));
            });
        }
        if (!pairs.isEmpty()) {
            if (!isPostgres()) {
                log.debug("pg-rls: not a PostgreSQL DataSource, transaction left untouched");
                return;
            }
            setConfig(pairs);
        }
        RlsTransactionState.bind(this);
    }

    /** Sets the tenant GUC and returns the value it had before. */
    String swapTenantGuc(String value) {
        return jdbc.execute((ConnectionCallback<String>) con -> {
            requireNoAutoCommit(con, "switch the tenant");
            try (PreparedStatement ps = con.prepareStatement(SWAP_SQL)) {
                ps.setString(1, settings.tenantGuc());
                ps.setString(2, settings.tenantGuc());
                ps.setString(3, value);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private void setConfig(List<String> namesAndValues) {
        StringBuilder sql = new StringBuilder("select ");
        for (int i = 0; i < namesAndValues.size(); i += 2) {
            sql.append(i == 0 ? "" : ", ").append("set_config(?, ?, true)");
        }
        jdbc.execute((ConnectionCallback<Void>) con -> {
            requireNoAutoCommit(con, "set " + namesAndValues.get(0));
            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                for (int i = 0; i < namesAndValues.size(); i++) {
                    ps.setString(i + 1, namesAndValues.get(i));
                }
                ps.execute();
            }
            return null;
        });
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = jdbc.execute((ConnectionCallback<Boolean>) con ->
                    "PostgreSQL".equalsIgnoreCase(con.getMetaData().getDatabaseProductName()));
            postgres = known;
        }
        return Boolean.TRUE.equals(known);
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RlsUsageException("TenantGuc." + operation + " needs an open Spring transaction: the setting "
                    + "is transaction-local and would vanish with the statement. Call it from a @Transactional "
                    + "method or inside TransactionTemplate.execute(...)");
        }
    }

    private static void requireNoAutoCommit(Connection con, String what) throws SQLException {
        if (con.getAutoCommit()) {
            throw new RlsUsageException("refusing to " + what + " on a connection in auto-commit: the value would "
                    + "die with this statement and every following query would see no tenant (zero rows, no "
                    + "error). This usually means a REQUIRED transaction joined one that already committed (for "
                    + "example inside an afterCommit callback): use REQUIRES_NEW there");
        }
    }
}
