// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.guardrails.support.Catalog;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * What the PostgreSQL catalog says about row-level security: which tables carry the tenant column, which have RLS
 * enabled and forced, and their policies. The single source every RLS guard reads — never the migration files, which
 * do not show what {@code DO} blocks, functions or dynamic SQL created.
 */
public final class RlsCatalog {

    private final Catalog catalog;
    private final List<String> schemas;

    /**
     * Reads the catalog of the given schemas.
     *
     * @param dataSource any role that can read the catalog
     * @param schemas schemas to inspect (e.g. {@code public})
     */
    public RlsCatalog(DataSource dataSource, List<String> schemas) {
        this.catalog = new Catalog(Objects.requireNonNull(dataSource, "dataSource"));
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("name at least one schema");
        }
        this.schemas = List.copyOf(schemas);
    }

    /**
     * A table (or partitioned table, or partition).
     *
     * @param schema schema name
     * @param name table name
     * @param oid relation oid
     * @param partitioned {@code relkind = 'p'}
     * @param partition {@code relispartition}
     * @param rlsEnabled {@code relrowsecurity}
     * @param rlsForced {@code relforcerowsecurity}
     * @param hasTenantColumn whether the tenant column exists
     * @param owner owning role
     */
    public record Table(String schema, String name, long oid, boolean partitioned, boolean partition,
            boolean rlsEnabled, boolean rlsForced, boolean hasTenantColumn, String owner) {

        public String id() {
            return schema + "." + name;
        }

        /** Quoted, schema-qualified name for SQL. */
        public String sql() {
            return "\"" + schema.replace("\"", "\"\"") + "\".\"" + name.replace("\"", "\"\"") + "\"";
        }
    }

    /**
     * A policy, with its expressions as PostgreSQL deparses them.
     *
     * @param table the table id
     * @param name policy name
     * @param command {@code r} SELECT, {@code a} INSERT, {@code w} UPDATE, {@code d} DELETE, {@code *} ALL
     * @param permissive {@code false} for RESTRICTIVE policies
     * @param using the USING expression, or {@code null}
     * @param withCheck the WITH CHECK expression, or {@code null}
     * @param roles role names the policy applies to ({@code public} for all)
     */
    public record Policy(String table, String name, String command, boolean permissive, String using,
            String withCheck, List<String> roles) {

        public String id() {
            return table + "#" + name;
        }

        /** The expression PostgreSQL applies to new rows: WITH CHECK, or USING when WITH CHECK is absent. */
        public String effectiveWithCheck() {
            return withCheck != null ? withCheck : using;
        }
    }

    /** Ordinary and partitioned tables of the schemas, with the given tenant column flagged. */
    public List<Table> tables(String tenantColumn) {
        List<Table> out = new ArrayList<>();
        for (String schema : schemas) {
            out.addAll(catalog.query("""
                    select n.nspname::text, c.relname::text, c.oid::bigint, c.relkind = 'p', c.relispartition,
                           c.relrowsecurity, c.relforcerowsecurity,
                           exists (select 1 from pg_attribute a
                                    where a.attrelid = c.oid and a.attname = ? and a.attnum > 0
                                      and not a.attisdropped),
                           pg_get_userbyid(c.relowner)::text
                      from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = ? and c.relkind in ('r', 'p')
                     order by c.relname
                    """, rs -> new Table(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4),
                    rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9)),
                    tenantColumn, schema));
        }
        return out;
    }

    /** Names of the tables with RLS enabled (unqualified, as {@code @Table(name)} would spell them). */
    public List<String> rlsTableNames(String tenantColumn) {
        return tables(tenantColumn).stream().filter(Table::rlsEnabled).map(Table::name).toList();
    }

    /** Every policy on the tables of the schemas. */
    public List<Policy> policies() {
        List<Policy> out = new ArrayList<>();
        for (String schema : schemas) {
            out.addAll(catalog.query("""
                    select n.nspname || '.' || c.relname, p.polname::text, p.polcmd::text, p.polpermissive,
                           pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid),
                           array(select case when r = 0 then 'public' else pg_get_userbyid(r)::text end
                                   from unnest(p.polroles) r)
                      from pg_policy p
                      join pg_class c on c.oid = p.polrelid
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = ?
                     order by c.relname, p.polname
                    """, rs -> new Policy(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                    rs.getString(5), rs.getString(6), texts(rs.getArray(7))), schema));
        }
        return out;
    }

    private static List<String> texts(Array array) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
