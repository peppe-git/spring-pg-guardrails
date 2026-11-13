// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Runs SQL the way production does — as a role subject to row-level security, with the tenant setting written —
 * from a test whose connection is a superuser (Testcontainers' default, which bypasses every policy).
 *
 * <p>Each call opens a transaction on the superuser connection, creates a {@code NOSUPERUSER NOBYPASSRLS} probe
 * role if needed, grants it access, switches to it with {@code SET LOCAL ROLE}, writes the GUCs, runs the work and
 * <b>rolls everything back</b>: the role, the grants and anything the work wrote. Plant the data you need with the
 * superuser beforehand, then look at it through the probe.
 *
 * <pre>{@code
 * long seen = probe.countAsTenant(tenantB, "select count(*) from invoices where tenant_id = ?", tenantA);
 * assertThat(seen).isZero();
 * }</pre>
 */
public final class RlsProbe {

    private static final Pattern ROLE = Pattern.compile("[a-z_][a-z0-9_]*");

    private final DataSource superuser;
    private final RlsSettings settings;
    private final String role;

    private RlsProbe(DataSource superuser, RlsSettings settings, String role) {
        this.superuser = Objects.requireNonNull(superuser, "superuser");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("probe role must be a plain lower-case identifier: " + role);
        }
        this.role = role;
    }

    public static RlsProbe on(DataSource superuser) {
        return new RlsProbe(superuser, RlsSettings.defaults(), "rls_probe");
    }

    public static RlsProbe on(DataSource superuser, RlsSettings settings) {
        return new RlsProbe(superuser, settings, "rls_probe");
    }

    /** Work done through the probe. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Session session) throws SQLException;
    }

    /** Runs {@code work} as the probe with {@code tenant} set; everything is rolled back. */
    public <T> T asTenant(Object tenant, Work<T> work) {
        return run(false, String.valueOf(Objects.requireNonNull(tenant, "tenant")), false, false, work);
    }

    /** Runs {@code work} as the probe with no tenant set. */
    public <T> T withoutTenant(Work<T> work) {
        return run(false, "", false, false, work);
    }

    /** Like {@link #withoutTenant} with strict mode on: a policy that is reached raises SQLSTATE RLS01. */
    public <T> T withoutTenantStrict(Work<T> work) {
        return run(false, "", false, true, work);
    }

    /** Runs {@code work} as the probe in a READ ONLY transaction with the admin read escape open. */
    public <T> T asAdminReader(Work<T> work) {
        return run(true, "", true, false, work);
    }

    /** Runs {@code work} as the probe in a READ WRITE transaction with the admin read escape open. */
    public <T> T asAdminInReadWrite(Work<T> work) {
        return run(false, "", true, false, work);
    }

    /** {@code select count(*) ...} as the probe with {@code tenant} set. */
    public long countAsTenant(Object tenant, String sql, Object... args) {
        return this.<Long>asTenant(tenant, s -> s.count(sql, args));
    }

    /** {@code select count(*) ...} as the probe with no tenant set. */
    public long countWithoutTenant(String sql, Object... args) {
        return this.<Long>withoutTenant(s -> s.count(sql, args));
    }

    /** {@code select count(*) ...} as the probe through the admin read escape, in a READ ONLY transaction. */
    public long countAsAdminReader(String sql, Object... args) {
        return this.<Long>asAdminReader(s -> s.count(sql, args));
    }

    private <T> T run(boolean readOnly, String tenant, boolean admin, boolean strict, Work<T> work) {
        try (Connection con = superuser.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (Statement st = con.createStatement()) {
                    st.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + role
                            + "') then create role " + role + " nologin nosuperuser nobypassrls noinherit; end if; "
                            + "end $$");
                    st.execute("do $$ declare s text; begin for s in select quote_ident(nspname) from pg_namespace "
                            + "where nspname not like 'pg\\_%' and nspname <> 'information_schema' loop "
                            + "execute 'grant usage on schema ' || s || ' to " + role + "'; "
                            + "execute 'grant select, insert, update, delete on all tables in schema ' || s || ' to "
                            + role + "'; execute 'grant usage, select on all sequences in schema ' || s || ' to "
                            + role + "'; end loop; end $$");
                    if (readOnly) {
                        st.execute("set local transaction_read_only = on");
                    }
                    st.execute("set local role " + role);
                }
                set(con, settings.tenantGuc(), tenant);
                if (admin) {
                    set(con, settings.adminReadGuc(), "on");
                }
                if (strict) {
                    set(con, settings.strictGuc(), "on");
                }
                return work.run(new Session(con));
            } finally {
                con.rollback();
            }
        } catch (SQLException e) {
            throw new ProbeException(e);
        }
    }

    private static void set(Connection con, String name, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("select set_config(?, ?, true)")) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.execute();
        }
    }

    /** The probe's connection, with two conveniences. */
    public static final class Session {

        private final Connection connection;

        Session(Connection connection) {
            this.connection = connection;
        }

        public Connection connection() {
            return connection;
        }

        /** The first column of the first row, as a long (for {@code select count(*) ...}). */
        public long count(String sql, Object... args) throws SQLException {
            try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        /** Rows changed by an INSERT, UPDATE or DELETE. */
        public int update(String sql, Object... args) throws SQLException {
            try (PreparedStatement ps = prepare(sql, args)) {
                return ps.executeUpdate();
            }
        }

        private PreparedStatement prepare(String sql, Object... args) throws SQLException {
            PreparedStatement ps = connection.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }
    }

    /** An SQL failure inside the probe; {@link #getCause()} is the {@link SQLException} with its SQLSTATE. */
    public static final class ProbeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ProbeException(SQLException cause) {
            super(cause.getSQLState() + ": " + cause.getMessage(), cause);
        }

        public String sqlState() {
            return ((SQLException) getCause()).getSQLState();
        }
    }
}
