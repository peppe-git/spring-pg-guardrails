// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.rls.RlsErrors;
import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The isolation test, generic: every table with row-level security and the tenant column, exercised as a role that
 * is subject to RLS.
 *
 * <p>Testcontainers connects as a superuser, and superusers bypass every policy. So this guard creates (inside its
 * own transaction) a {@code NOSUPERUSER NOBYPASSRLS} probe role, and for each table:
 * <ol>
 *   <li>plants a row for tenant A, as the superuser, with foreign-key triggers off
 *       ({@code session_replication_role = replica}) so that no parent rows are needed. The row is generated from
 *       the catalog: tenant column, then every {@code NOT NULL} column without a default (enum labels and set-like
 *       {@code CHECK} values are honoured). If that fails, the table is <b>inconclusive</b>: give it a
 *       {@link #fixture(String, String)} or exempt it by name;</li>
 *   <li>as the probe with tenant A: sees its own row (positive control — a policy that hides everything would
 *       otherwise pass every check below);</li>
 *   <li>as the probe with tenant B: sees no row of another tenant, updates none, deletes none, and cannot insert
 *       a row that belongs to A (the insertion is first tried as A, to prove the row is insertable);</li>
 *   <li>as the probe with no tenant: sees, updates and deletes nothing (zero rows or a strict-mode error);</li>
 *   <li>when a policy of the table mentions the admin read escape: with the escape open and no tenant, a
 *       {@code READ ONLY} transaction reads A's row, and a read-write one cannot delete, update or insert it;</li>
 *   <li>for a partitioned table: each leaf partition, queried directly by B, hides A's row.</li>
 * </ol>
 * Everything happens in one transaction that is rolled back: no role, grant or row survives.
 *
 * <p>Requires a <b>superuser</b> connection (to switch {@code session_replication_role} and to {@code SET ROLE}).
 */
public final class RlsIsolation extends Guard<RlsIsolation> {

    private static final String INSUFFICIENT_PRIVILEGE = RlsErrors.POLICY_VIOLATION_SQLSTATE;
    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");
    private static final Pattern SET_LIKE = Pattern.compile("(?i)=\\s*any\\s*\\(+\\s*array\\[|\\bin\\s*\\(");
    private static final Pattern PROBE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private final DataSource superuser;
    private List<String> schemas = List.of("public");
    private String tenantColumn = "tenant_id";
    private RlsSettings settings = RlsSettings.defaults();
    private String probeRole = "rls_isolation_probe";
    private final Map<String, String> fixtures = new LinkedHashMap<>();
    private final List<String> adminMarkers = new ArrayList<>();

    private RlsIsolation(DataSource superuser) {
        this.superuser = Objects.requireNonNull(superuser, "superuser");
        settings(RlsSettings.defaults());
    }

    /** Isolation of every RLS table of {@code public}; {@code superuser} must connect as a superuser. */
    public static RlsIsolation of(DataSource superuser) {
        return new RlsIsolation(superuser);
    }

    public RlsIsolation schemas(String... names) {
        this.schemas = List.of(names);
        return this;
    }

    public RlsIsolation tenantColumn(String column) {
        this.tenantColumn = Objects.requireNonNull(column);
        return this;
    }

    /** GUC names to write (and the admin escape markers derived from them). */
    public RlsIsolation settings(RlsSettings s) {
        this.settings = Objects.requireNonNull(s);
        adminMarkers.clear();
        adminMarkers.addAll(List.of(s.adminReadGuc(), "admin_read_enabled("));
        return this;
    }

    /** Text that marks a legacy admin escape in a policy (e.g. its GUC name). */
    public RlsIsolation adminMarker(String text) {
        adminMarkers.add(Objects.requireNonNull(text));
        return this;
    }

    /** Name of the temporary probe role (created and dropped inside the guard's transaction). */
    public RlsIsolation probeRole(String role) {
        if (!PROBE_NAME.matcher(role).matches()) {
            throw new IllegalArgumentException("probe role must be a plain lower-case identifier: " + role);
        }
        this.probeRole = role;
        return this;
    }

    /**
     * An INSERT that creates one valid row of {@code table} ({@code schema.table}) for a tenant. Write
     * {@code :tenant} where the tenant goes and {@code :unique} where a fresh random text is needed, e.g.
     * {@code insert into invoices (tenant_id, number, total) values (:tenant, :unique, 10)}.
     */
    public RlsIsolation fixture(String table, String insertSql) {
        if (!insertSql.contains(":tenant")) {
            throw new IllegalArgumentException("the fixture of " + table + " must contain :tenant");
        }
        fixtures.put(table, insertSql);
        return this;
    }

    @Override
    protected String name() {
        return "RlsIsolation";
    }

    @Override
    protected String rationale() {
        return "a policy can exist, be enabled and forced, and still leak: only exercising it as a role that is "
                + "subject to RLS shows it, and Testcontainers connects as a superuser that bypasses every policy";
    }

    @Override
    protected String remedy() {
        return "fix the policy (rls.enable_tenant_rls(...) writes one per command); for an inconclusive table add a "
                + "fixture(table, sql) or exempt it with the reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        List<RlsCatalog.Table> tables = new RlsCatalog(superuser, schemas).tables(tenantColumn).stream()
                .filter(t -> t.hasTenantColumn() && t.rlsEnabled() && !t.partition())
                .toList();
        Map<String, List<RlsCatalog.Policy>> policies = new LinkedHashMap<>();
        for (RlsCatalog.Policy p : new RlsCatalog(superuser, schemas).policies()) {
            policies.computeIfAbsent(p.table(), k -> new ArrayList<>()).add(p);
        }
        for (String id : fixtures.keySet()) {
            if (tables.stream().noneMatch(t -> t.id().equals(id))) {
                throw new IllegalArgumentException("fixture for " + id + ", which is not an RLS table with "
                        + tenantColumn + " in " + schemas);
            }
        }
        try (Connection con = superuser.getConnection()) {
            con.setAutoCommit(false);
            try {
                prepare(con);
                for (RlsCatalog.Table table : tables) {
                    boolean admin = policies.getOrDefault(table.id(), List.of()).stream()
                            .anyMatch(p -> mentions(p.using()) || mentions(p.withCheck()));
                    verify(con, table, admin, collector);
                }
            } finally {
                con.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("isolation check failed to run: " + e.getMessage(), e);
        }
    }

    private boolean mentions(String expression) {
        return expression != null && adminMarkers.stream().anyMatch(expression::contains);
    }

    private void prepare(Connection con) throws SQLException {
        boolean isSuperuser = queryLong(con, "select count(*) from pg_roles where rolname = current_user "
                + "and rolsuper") > 0;
        if (!isSuperuser) {
            throw new AssertionError("RlsIsolation needs a superuser connection (to disable foreign-key triggers "
                    + "while planting rows and to SET ROLE to the probe); Testcontainers' default user is one");
        }
        try (Statement st = con.createStatement()) {
            st.execute("set local session_replication_role = replica");
            if (queryLong(con, "select count(*) from pg_roles where rolname = '" + probeRole + "'") == 0) {
                st.execute("create role " + probeRole + " nologin nosuperuser nobypassrls noinherit");
            }
            List<String> userSchemas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("select quote_ident(nspname) from pg_namespace "
                    + "where nspname not like 'pg\\_%' and nspname <> 'information_schema'")) {
                while (rs.next()) {
                    userSchemas.add(rs.getString(1));
                }
            }
            for (String s : userSchemas) {
                st.execute("grant usage on schema " + s + " to " + probeRole);
                st.execute("grant select, insert, update, delete on all tables in schema " + s + " to " + probeRole);
                st.execute("grant usage, select on all sequences in schema " + s + " to " + probeRole);
            }
        }
    }

    private void verify(Connection con, RlsCatalog.Table table, boolean admin, Collector collector)
            throws SQLException {
        Row generator;
        try {
            generator = rowFor(con, table);
        } catch (Inconclusive e) {
            collector.inconclusive(table.id(), e.getMessage());
            return;
        }
        String tenantType = generator.tenantType;
        String a = tenantValue(tenantType);
        String b = tenantValue(tenantType);
        String col = quote(tenantColumn);
        String mine = "select count(*) from " + table.sql() + " where " + col + " = ?::" + tenantType;

        Outcome planted = step(con, c -> generator.insert(c, a), false);
        if (!planted.ok()) {
            collector.inconclusive(table.id(), "cannot plant a row for tenant A: " + planted.message()
                    + " — add fixture(\"" + table.id() + "\", \"insert ... (:tenant, ...)\")");
            return;
        }
        List<String> problems = new ArrayList<>();

        Outcome ownRead = step(con, c -> asTenant(c, a, () -> queryLong(c, mine, a)), true);
        if (!ownRead.ok() || ownRead.count() < 1) {
            problems.add("tenant A cannot read its own row (" + ownRead.describe()
                    + "): the policy hides everything, so the other checks would pass for the wrong reason");
        }

        Outcome foreignRead = step(con, c -> asTenant(c, b, () -> queryLong(c, "select count(*) from "
                + table.sql() + " where " + col + " is not null and " + col + " <> ?::" + tenantType, b)), true);
        if (!foreignRead.denied()) {
            problems.add("tenant B sees " + foreignRead.count() + " row(s) of other tenants");
        }
        Outcome foreignUpdate = step(con, c -> asTenant(c, b, () -> update(c, "update " + table.sql() + " set "
                + col + " = " + col + " where " + col + " = ?::" + tenantType, a)), true);
        if (!foreignUpdate.denied()) {
            problems.add("tenant B updates " + foreignUpdate.count() + " row(s) of tenant A");
        }
        Outcome foreignDelete = step(con, c -> asTenant(c, b, () -> update(c, "delete from " + table.sql()
                + " where " + col + " = ?::" + tenantType, a)), true);
        if (!foreignDelete.denied()) {
            problems.add("tenant B deletes " + foreignDelete.count() + " row(s) of tenant A");
        }

        Row fresh = generator.fresh();
        Outcome ownInsert = step(con, c -> asTenant(c, a, () -> fresh.insert(c, a)), true);
        if (ownInsert.ok()) {
            Outcome forged = step(con, c -> asTenant(c, b, () -> fresh.insert(c, a)), true);
            if (forged.ok()) {
                problems.add("tenant B inserts a row that belongs to tenant A (the WITH CHECK lets it through)");
            } else if (!forged.isPolicyError()) {
                collector.note(table.id() + ": forged insert failed for another reason (" + forged.message() + ")");
            }
        } else if (ownInsert.isPolicyError()) {
            collector.note(table.id() + ": tenants cannot insert rows at all; insert isolation not exercised");
        } else {
            collector.inconclusive(table.id(), "the probe cannot insert a generated row as its own tenant: "
                    + ownInsert.message() + " — add a fixture");
            return;
        }

        Outcome blindRead = step(con, c -> asTenant(c, "", () -> queryLong(c, mine, a)), true);
        if (!blindRead.denied()) {
            problems.add("with no tenant set, " + blindRead.count() + " row(s) are visible (fail-open)");
        }
        Outcome blindDelete = step(con, c -> asTenant(c, "", () -> update(c, "delete from " + table.sql()
                + " where " + col + " = ?::" + tenantType, a)), true);
        if (!blindDelete.denied()) {
            problems.add("with no tenant set, a DELETE removes " + blindDelete.count() + " row(s)");
        }

        if (admin) {
            adminChecks(con, table, generator, mine, a, problems, collector);
        }
        if (table.partitioned()) {
            for (String leaf : leaves(con, table)) {
                Outcome leak = step(con, c -> asTenant(c, b, () -> queryLong(c, "select count(*) from " + leaf
                        + " where " + col + " = ?::" + tenantType, a)), true);
                if (!leak.denied()) {
                    problems.add("leaf partition " + leaf + " shows tenant A's row to tenant B when queried directly");
                }
            }
        }

        if (problems.isEmpty()) {
            collector.examined();
        } else {
            collector.violation(table.id(), String.join("; ", problems));
        }
    }

    private void adminChecks(Connection con, RlsCatalog.Table table, Row generator, String mine, String a,
            List<String> problems, Collector collector) throws SQLException {
        String col = quote(tenantColumn);
        String type = generator.tenantType;
        Outcome adminRead = step(con, c -> {
            try (Statement st = c.createStatement()) {
                st.execute("set local transaction_read_only = on");
            }
            return asAdmin(c, () -> queryLong(c, mine, a));
        }, true);
        if (!adminRead.ok()) {
            collector.note(table.id() + ": admin read escape not exercised (" + adminRead.message() + ")");
        } else if (adminRead.count() < 1) {
            problems.add("a policy mentions the admin read escape, but a READ ONLY transaction with the escape "
                    + "open cannot read tenant A's row");
        }
        Outcome adminDelete = step(con, c -> asAdmin(c, () -> update(c, "delete from " + table.sql() + " where "
                + col + " = ?::" + type, a)), true);
        if (!adminDelete.denied()) {
            problems.add("the admin read escape lets a DELETE remove " + adminDelete.count() + " row(s) of tenant A "
                    + "(the escape is inside a policy that also covers DELETE)");
        }
        Outcome adminUpdate = step(con, c -> asAdmin(c, () -> update(c, "update " + table.sql() + " set " + col
                + " = " + col + " where " + col + " = ?::" + type, a)), true);
        if (!adminUpdate.denied()) {
            problems.add("the admin read escape lets an UPDATE touch " + adminUpdate.count() + " row(s) of tenant A");
        }
        Row fresh = generator.fresh();
        Outcome adminInsert = step(con, c -> asAdmin(c, () -> fresh.insert(c, a)), true);
        if (adminInsert.ok()) {
            problems.add("the admin read escape lets an INSERT write a row for tenant A");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // row generation

    private Row rowFor(Connection con, RlsCatalog.Table table) throws SQLException {
        String fixture = fixtures.get(table.id());
        String tenantType = null;
        List<Column> required = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                select a.attname::text,
                       format_type(a.atttypid, a.atttypmod),
                       bt.typname::text,
                       bt.typtype::text,
                       bt.typcategory::text,
                       a.attnotnull,
                       a.atthasdef or a.attidentity <> '' or a.attgenerated <> '',
                       coalesce(nullif(a.atttypmod, -1), t.typtypmod),
                       bt.oid
                  from pg_attribute a
                  join pg_type t on t.oid = a.atttypid
                  join pg_type bt on bt.oid = case when t.typtype = 'd' then t.typbasetype else t.oid end
                 where a.attrelid = ? and a.attnum > 0 and not a.attisdropped
                 order by a.attnum
                """)) {
            ps.setLong(1, table.oid());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name.equals(tenantColumn)) {
                        tenantType = rs.getString(2);
                        continue;
                    }
                    if (rs.getBoolean(6) && !rs.getBoolean(7)) {
                        required.add(new Column(name, rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getInt(8), rs.getLong(9)));
                    }
                }
            }
        }
        if (tenantType == null) {
            throw new Inconclusive("no column " + tenantColumn);
        }
        if (fixture != null) {
            return new Row(tenantType, table, null, fixture);
        }
        List<Value> values = new ArrayList<>();
        for (Column c : required) {
            values.add(valueFor(con, table, c));
        }
        return new Row(tenantType, table, values, null);
    }

    private Value valueFor(Connection con, RlsCatalog.Table table, Column c) throws SQLException {
        List<String> allowed = setLikeValues(con, table.oid(), c.name());
        if (!allowed.isEmpty()) {
            return Value.bound(c, allowed.get(0));
        }
        if ("e".equals(c.typtype())) {
            List<String> labels = strings(con, "select enumlabel::text from pg_enum where enumtypid = ? "
                    + "order by enumsortorder", c.baseOid());
            if (!labels.isEmpty()) {
                return Value.bound(c, labels.get(0));
            }
        }
        if ("A".equals(c.category())) {
            return Value.bound(c, "{}");
        }
        return switch (c.baseType()) {
            case "int2" -> Value.random(c, () -> String.valueOf(ThreadLocalRandom.current().nextInt(1, 32_000)));
            case "int4", "oid" -> Value.random(c, () -> String.valueOf(ThreadLocalRandom.current()
                    .nextInt(1, Integer.MAX_VALUE)));
            case "int8" -> Value.random(c, () -> String.valueOf(ThreadLocalRandom.current()
                    .nextLong(1, Long.MAX_VALUE / 2)));
            case "numeric", "float4", "float8", "money" -> Value.random(c, () -> String.valueOf(
                    ThreadLocalRandom.current().nextInt(1, 10)));
            case "text", "varchar", "bpchar", "name", "citext" -> Value.random(c, () -> randomText(c));
            case "bool" -> Value.bound(c, "false");
            case "uuid" -> Value.random(c, () -> UUID.randomUUID().toString());
            case "date", "timestamp", "timestamptz", "time", "timetz" -> Value.expression(c, "now()::" + c.type());
            case "interval" -> Value.bound(c, "1 day");
            case "json", "jsonb" -> Value.bound(c, "{}");
            case "bytea" -> Value.bound(c, "\\x00");
            case "inet", "cidr" -> Value.bound(c, "127.0.0.1");
            default -> throw new Inconclusive("cannot generate a value for " + c.name() + " (" + c.type()
                    + "); add a fixture");
        };
    }

    private static String randomText(Column c) {
        String text = "rls" + UUID.randomUUID().toString().replace("-", "");
        int max = c.typmod() > 4 ? c.typmod() - 4 : text.length();
        return text.substring(0, Math.min(max, text.length()));
    }

    private static List<String> setLikeValues(Connection con, long relation, String column) throws SQLException {
        List<String> defs = strings(con, """
                select pg_get_constraintdef(c.oid)
                  from pg_constraint c
                  join pg_attribute a on a.attrelid = c.conrelid and a.attname = ?
                 where c.conrelid = ? and c.contype = 'c' and c.conkey = array[a.attnum]::int2[]
                """, column, relation);
        for (String def : defs) {
            if (SET_LIKE.matcher(def).find()) {
                List<String> values = new ArrayList<>();
                Matcher m = LITERAL.matcher(def);
                while (m.find()) {
                    values.add(m.group(1).replace("''", "'"));
                }
                return values;
            }
        }
        return List.of();
    }

    private List<String> leaves(Connection con, RlsCatalog.Table table) throws SQLException {
        return strings(con, "select relid::regclass::text from pg_partition_tree(?::regclass) where isleaf",
                table.sql());
    }

    private String tenantValue(String type) {
        String base = type.toLowerCase(java.util.Locale.ROOT);
        if (base.equals("uuid")) {
            return UUID.randomUUID().toString();
        }
        if (base.equals("bigint") || base.equals("integer") || base.equals("smallint")) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(1, 32_000));
        }
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---------------------------------------------------------------------------------------------------------
    // execution

    @FunctionalInterface
    private interface Sql<T> {
        T run(Connection con) throws SQLException;
    }

    @FunctionalInterface
    private interface Body<T> {
        T run() throws SQLException;
    }

    private <T> T asTenant(Connection con, String tenant, Body<T> body) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("set local role " + probeRole);
        }
        setConfig(con, settings.tenantGuc(), tenant);
        return body.run();
    }

    private <T> T asAdmin(Connection con, Body<T> body) throws SQLException {
        return asTenant(con, "", () -> {
            setConfig(con, settings.adminReadGuc(), "on");
            return body.run();
        });
    }

    private static void setConfig(Connection con, String name, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("select set_config(?, ?, true)")) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.execute();
        }
    }

    /** Runs {@code work} inside a savepoint; with {@code undo}, always rolls back to it. */
    private static Outcome step(Connection con, Sql<Object> work, boolean undo) throws SQLException {
        Savepoint sp = con.setSavepoint();
        try {
            Object result = work.run(con);
            if (undo) {
                con.rollback(sp);
            }
            con.releaseSavepoint(sp);
            return new Outcome(true, result instanceof Number n ? n.longValue() : 0, null, null);
        } catch (SQLException e) {
            con.rollback(sp);
            con.releaseSavepoint(sp);
            return new Outcome(false, 0, e.getSQLState(), e.getMessage());
        }
    }

    private static long queryLong(Connection con, String sql, Object... args) throws SQLException {
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

    private static long update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static List<String> strings(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** The result of one step. A step is "denied" when it saw/touched nothing or failed with a policy error. */
    private record Outcome(boolean ok, long count, String sqlState, String message) {

        boolean isPolicyError() {
            return !ok && (INSUFFICIENT_PRIVILEGE.equals(sqlState) || RlsErrors.STRICT_MODE_SQLSTATE.equals(sqlState)
                    || "25006".equals(sqlState) /* read_only_sql_transaction */);
        }

        boolean denied() {
            return ok ? count == 0 : isPolicyError();
        }

        String describe() {
            return ok ? count + " row(s)" : "error " + sqlState + ": " + message;
        }
    }

    private record Column(String name, String type, String baseType, String typtype, String category, int typmod,
            long baseOid) {
    }

    /** A value for a column: bound text cast to the column type, or an SQL expression. */
    private record Value(Column column, String fixed, java.util.function.Supplier<String> random, String expression) {

        static Value bound(Column c, String text) {
            return new Value(c, text, null, null);
        }

        static Value random(Column c, java.util.function.Supplier<String> supplier) {
            return new Value(c, null, supplier, null);
        }

        static Value expression(Column c, String sql) {
            return new Value(c, null, null, sql);
        }

        Value fresh() {
            return random == null ? this : new Value(column, random.get(), random, null);
        }
    }

    /** One insertable row shape: generated values, or a user fixture. */
    private final class Row {

        private final String tenantType;
        private final RlsCatalog.Table table;
        private final List<Value> values;
        private final String fixture;

        Row(String tenantType, RlsCatalog.Table table, List<Value> values, String fixture) {
            this.tenantType = tenantType;
            this.table = table;
            this.values = values == null ? null : values.stream().map(Value::fresh).toList();
            this.fixture = fixture;
        }

        Row fresh() {
            return new Row(tenantType, table, values, fixture);
        }

        Object insert(Connection con, String tenant) throws SQLException {
            if (fixture != null) {
                return insertFixture(con, tenant);
            }
            StringBuilder cols = new StringBuilder(quote(tenantColumn));
            StringBuilder exprs = new StringBuilder("?::" + tenantType);
            List<String> args = new ArrayList<>();
            args.add(tenant);
            for (Value v : values) {
                cols.append(", ").append(quote(v.column().name()));
                if (v.expression() != null) {
                    exprs.append(", ").append(v.expression());
                } else {
                    exprs.append(", ?::").append(v.column().type());
                    args.add(v.fixed());
                }
            }
            try (PreparedStatement ps = con.prepareStatement("insert into " + table.sql() + " (" + cols
                    + ") values (" + exprs + ")")) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setString(i + 1, args.get(i));
                }
                return ps.executeUpdate();
            }
        }

        private Object insertFixture(Connection con, String tenant) throws SQLException {
            List<String> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder();
            Matcher m = Pattern.compile(":tenant\\b|:unique\\b").matcher(fixture);
            int last = 0;
            while (m.find()) {
                sql.append(fixture, last, m.start());
                if (m.group().equals(":tenant")) {
                    sql.append("?::").append(tenantType);
                    args.add(tenant);
                } else {
                    sql.append("?");
                    args.add("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                }
                last = m.end();
            }
            sql.append(fixture.substring(last));
            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setString(i + 1, args.get(i));
                }
                return ps.executeUpdate();
            }
        }
    }

    private static final class Inconclusive extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Inconclusive(String message) {
            super(message, null, false, false);
        }
    }
}
