// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The SQL shipped with the library, exercised on a real PostgreSQL as a role that is subject to row-level security
 * (the superuser of the container would bypass every policy and prove nothing).
 */
class SqlPoliciesIT {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @BeforeAll
    static void database() {
        TestPostgres.start();
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection con) throws SQLException;
    }

    /** One transaction as the runtime role, with the given GUCs, always rolled back unless {@code commit}. */
    private static <T> T tx(boolean readOnly, boolean commit, Map<String, String> gucs, Work<T> work)
            throws SQLException {
        try (Connection con = TestPostgres.runtime().getConnection()) {
            con.setReadOnly(readOnly);
            con.setAutoCommit(false);
            for (Map.Entry<String, String> g : gucs.entrySet()) {
                try (PreparedStatement ps = con.prepareStatement("select set_config(?, ?, true)")) {
                    ps.setString(1, g.getKey());
                    ps.setString(2, g.getValue());
                    ps.execute();
                }
            }
            try {
                return work.run(con);
            } finally {
                if (commit) {
                    con.commit();
                } else {
                    con.rollback();
                }
            }
        }
    }

    private static long query(boolean readOnly, Map<String, String> gucs, String sql, Object... args)
            throws SQLException {
        return tx(readOnly, false, gucs, con -> count(con, sql, args));
    }

    private static int change(Map<String, String> gucs, String sql, Object... args) throws SQLException {
        return tx(false, false, gucs, con -> update(con, sql, args));
    }

    private static Map<String, String> tenant(UUID t) {
        return Map.of(RlsSettings.DEFAULT_TENANT_GUC, t.toString());
    }

    private static long count(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private void seedOneNoteEach() throws SQLException {
        tx(false, true, tenant(a), con -> update(con, "insert into notes(tenant_id, body) values (?, 'a')", a));
        tx(false, true, tenant(b), con -> update(con, "insert into notes(tenant_id, body) values (?, 'b')", b));
    }

    @Test
    void aTenantSeesAndChangesOnlyItsOwnRows() throws SQLException {
        seedOneNoteEach();
        assertThat(query(false, tenant(a), "select count(*) from notes where tenant_id in (?, ?)", a, b))
                .isEqualTo(1);
        assertThat(change(tenant(a), "update notes set body = 'x' where tenant_id = ?", b))
                .as("UPDATE of another tenant's row").isZero();
        assertThat(change(tenant(a), "delete from notes where tenant_id = ?", b))
                .as("DELETE of another tenant's row").isZero();
        assertThatThrownBy(() -> tx(false, false, tenant(a),
                con -> update(con, "insert into notes(tenant_id, body) values (?, 'forged')", b)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(RlsErrors.POLICY_VIOLATION_SQLSTATE));
        assertThatThrownBy(() -> tx(false, false, tenant(a),
                con -> update(con, "update notes set tenant_id = ? where tenant_id = ?", b, a)))
                .as("moving an own row to another tenant").isInstanceOf(SQLException.class);
    }

    @Test
    void withoutTenantAndWithoutStrictModeTheTableLooksEmpty() throws SQLException {
        seedOneNoteEach();
        assertThat(query(false, Map.of(), "select count(*) from notes")).isZero();
        assertThat(change(Map.of(), "delete from notes")).isZero();
    }

    @Test
    void strictModeTurnsTheMissingTenantIntoAnErrorForReadsAndWrites() throws SQLException {
        seedOneNoteEach();
        Map<String, String> strict = Map.of(RlsSettings.DEFAULT_STRICT_GUC, "on");
        assertThatThrownBy(() -> tx(false, false, strict, con -> count(con, "select count(*) from notes"))).satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).as("SQLSTATE RLS01: %s", e).isTrue());
        assertThatThrownBy(() -> tx(false, false, strict,
                con -> update(con, "insert into notes(tenant_id, body) values (?, 'x')", a)))
                .satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).as("SQLSTATE RLS01: %s", e).isTrue());
    }

    @Test
    void theAdminReadEscapeReadsEveryTenantButOnlyInsideAReadOnlyTransaction() throws SQLException {
        seedOneNoteEach();
        Map<String, String> admin = Map.of(RlsSettings.DEFAULT_ADMIN_READ_GUC, "on");
        assertThat(query(true, admin, "select count(*) from notes where tenant_id in (?, ?)", a, b))
                .isEqualTo(2);
        assertThat(query(false, admin, "select count(*) from notes where tenant_id in (?, ?)", a, b))
                .as("same escape in a READ WRITE transaction").isZero();
    }

    @Test
    void theAdminReadEscapeNeverLetsAWriteThrough() throws SQLException {
        seedOneNoteEach();
        Map<String, String> admin = Map.of(RlsSettings.DEFAULT_ADMIN_READ_GUC, "on");
        assertThat(change(admin, "delete from notes where tenant_id in (?, ?)", a, b))
                .as("cross-tenant DELETE under the escape").isZero();
        assertThat(change(admin, "update notes set body = 'x' where tenant_id in (?, ?)", a, b))
                .as("cross-tenant UPDATE under the escape").isZero();
        Map<String, String> adminStrict = Map.of(RlsSettings.DEFAULT_ADMIN_READ_GUC, "on",
                RlsSettings.DEFAULT_STRICT_GUC, "on");
        assertThatThrownBy(() -> tx(false, false, adminStrict,
                con -> update(con, "delete from notes where tenant_id in (?, ?)", a, b)))
                .as("in strict mode the attempt is an error, not zero rows")
                .satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).isTrue());
    }

    /**
     * The trap the per-command policies exist for, reproduced: one {@code FOR ALL} policy with the escape in its
     * {@code USING}. {@code WITH CHECK} only guards new rows; {@code DELETE} has none, so under the escape it removes
     * rows of every tenant.
     */
    @Test
    void aSingleForAllPolicyWithTheEscapeInUsingLetsACrossTenantDeleteThrough() throws SQLException {
        TestPostgres.execute("""
                drop table if exists legacy_items;
                create table legacy_items (tenant_id uuid not null, v text);
                alter table legacy_items enable row level security;
                alter table legacy_items force row level security;
                create policy legacy_isolation on legacy_items
                  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                         or current_setting('app.rls_admin_read', true) = 'on')
                  with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
                grant select, insert, update, delete on legacy_items to app_runtime;
                """);
        tx(false, true, tenant(a), con -> update(con, "insert into legacy_items values (?, 'a')", a));
        int deleted = tx(false, false, Map.of(RlsSettings.DEFAULT_ADMIN_READ_GUC, "on"),
                con -> update(con, "delete from legacy_items where tenant_id = ?", a));
        assertThat(deleted).as("the legacy shape lets the read escape delete another tenant's row").isEqualTo(1);
    }

    @Test
    void partitionsAreProtectedEvenWhenQueriedDirectly() throws SQLException {
        TestPostgres.execute("""
                drop table if exists events;
                create table events (tenant_id uuid not null, at date not null, v text) partition by range (at);
                create table events_2026 partition of events for values from ('2026-01-01') to ('2027-01-01');
                select rls.enable_tenant_rls('events');
                grant select, insert, update, delete on events, events_2026 to app_runtime;
                """);
        tx(false, true, tenant(a), con -> update(con, "insert into events values (?, date '2026-05-01', 'a')", a));
        assertThat(query(false, tenant(b), "select count(*) from events_2026 where tenant_id = ?", a))
                .as("reading the partition directly, bypassing the parent").isZero();
        assertThat(query(false, tenant(a), "select count(*) from events_2026 where tenant_id = ?", a))
                .isEqualTo(1);
    }

    @Test
    void theInlineTemplateRendersAndIsolates() throws SQLException {
        TestPostgres.execute("drop table if exists inline_items; create table inline_items (tenant_id uuid not null, v text);"
                + "grant select, insert, update, delete on inline_items to app_runtime;");
        TestPostgres.execute(RlsSql.render(RlsSql.INLINE_POLICY, Map.of(
                "table", "inline_items", "tenant_column", "tenant_id", "tenant_type", "uuid",
                "tenant_guc", RlsSettings.DEFAULT_TENANT_GUC, "admin_read_guc", RlsSettings.DEFAULT_ADMIN_READ_GUC)));
        tx(false, true, tenant(a), con -> update(con, "insert into inline_items values (?, 'a')", a));
        assertThat(query(false, tenant(b), "select count(*) from inline_items where tenant_id = ?", a))
                .isZero();
        assertThat(change(Map.of(RlsSettings.DEFAULT_ADMIN_READ_GUC, "on"),
                "delete from inline_items where tenant_id = ?", a))
                .as("the separate FOR SELECT escape does not widen DELETE").isZero();
    }

    @Test
    void renderingRefusesToLeaveAPlaceholderUnresolved() {
        assertThatThrownBy(() -> RlsSql.render(RlsSql.INSTALL, Map.of("rls_schema", "rls")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${");
    }

    @Test
    void theRuntimeRoleOfTheScriptIsSubjectToRlsAndTheSuperuserIsNot() {
        assertThat(RuntimeRoleCheck.inspect(TestPostgres.runtime()).problems()).isEmpty();
        RuntimeRoleCheck.Report superuser = RuntimeRoleCheck.inspect(TestPostgres.superuser());
        assertThat(superuser.isSubjectToRls()).isFalse();
        assertThat(superuser.problems()).anySatisfy(p -> assertThat(p).contains("superuser"));
    }

    @Test
    void aRoleThatCanSetRoleToABypassingRoleIsReported() {
        TestPostgres.execute("""
                do $$ begin
                  if not exists (select 1 from pg_roles where rolname = 'sneaky_bypass') then
                    create role sneaky_bypass nologin bypassrls;
                  end if;
                  if not exists (select 1 from pg_roles where rolname = 'sneaky_app') then
                    create role sneaky_app login password 'sneaky-test-only';
                  end if;
                end $$;
                grant sneaky_bypass to sneaky_app;
                """);
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(TestPostgres.jdbcUrl());
        ds.setUser("sneaky_app");
        ds.setPassword("sneaky-test-only");
        assertThat(RuntimeRoleCheck.inspect(ds).problems())
                .anySatisfy(p -> assertThat(p).contains("sneaky_bypass").contains("SET ROLE"));
    }
}
