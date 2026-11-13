// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks that the role the application connects as is actually subject to row-level security.
 *
 * <p>Every policy rests on one premise that lives outside the code: the runtime role is not a superuser, has no
 * {@code BYPASSRLS}, cannot {@code SET ROLE} to a role that has either, and does not own a table whose RLS is not
 * {@code FORCE}d (owners skip non-forced policies). If any of these breaks — {@code BYPASSRLS} is the natural
 * shortcut in front of a query that "does not see its rows" — every policy stops filtering and no integration test
 * notices, because Testcontainers connects as a superuser anyway.
 */
public final class RuntimeRoleCheck {

    /** What to do at application start-up when the role is not subject to RLS. */
    public enum Mode {
        /** Do not check. */
        OFF,
        /** Log a warning (default: integration tests usually connect as a superuser). */
        WARN,
        /** Refuse to start. Recommended in production. */
        FAIL
    }

    private static final String QUERY = """
            select r.rolname,
                   r.rolsuper,
                   r.rolbypassrls,
                   coalesce((select array_agg(p.rolname::text order by p.rolname)
                               from pg_roles p
                              where p.oid <> r.oid
                                and (p.rolsuper or p.rolbypassrls)
                                and pg_has_role(r.oid, p.oid, 'MEMBER')), '{}') as privileged_reachable,
                   coalesce((select array_agg(c.oid::regclass::text order by c.oid::regclass::text)
                               from pg_class c
                              where c.relowner = r.oid
                                and c.relrowsecurity
                                and not c.relforcerowsecurity), '{}') as owned_not_forced
              from pg_roles r
             where r.rolname = current_user
            """;

    private RuntimeRoleCheck() {
    }

    /** Inspects the role of a connection taken from {@code dataSource}. */
    public static Report inspect(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbcTemplate(dataSource).queryForObject(QUERY, (rs, i) -> new Report(
                rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), texts(rs, 4), texts(rs, 5)));
    }

    private static List<String> texts(ResultSet rs, int column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            out.add(String.valueOf(o));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * What the catalog says about a role.
     *
     * @param role the role name
     * @param superuser {@code rolsuper}
     * @param bypassRls {@code rolbypassrls}
     * @param privilegedRolesReachable superuser or {@code BYPASSRLS} roles this role is a member of (it can
     *     {@code SET ROLE} to them)
     * @param ownedTablesWithoutForce tables the role owns with RLS enabled but not forced
     */
    public record Report(String role, boolean superuser, boolean bypassRls, List<String> privilegedRolesReachable,
            List<String> ownedTablesWithoutForce) {

        /** Whether every policy applies to this role. */
        public boolean isSubjectToRls() {
            return problems().isEmpty();
        }

        /** One line per reason the role escapes row-level security; empty when it does not. */
        public List<String> problems() {
            List<String> out = new ArrayList<>();
            if (superuser) {
                out.add("role '" + role + "' is a superuser: superusers bypass every policy, even FORCEd ones");
            }
            if (bypassRls) {
                out.add("role '" + role + "' has BYPASSRLS: every policy is skipped for it");
            }
            if (!privilegedRolesReachable.isEmpty()) {
                out.add("role '" + role + "' is a member of " + privilegedRolesReachable
                        + ", which bypass RLS; any SQL running as it can SET ROLE to them");
            }
            if (!ownedTablesWithoutForce.isEmpty()) {
                out.add("role '" + role + "' owns " + ownedTablesWithoutForce
                        + " whose RLS is enabled but not FORCEd: owners skip non-forced policies");
            }
            return out;
        }
    }
}
