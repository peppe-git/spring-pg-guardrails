// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.guardrails.support.Catalog;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The roles your application connects as are subject to row-level security — checked on the role itself, in the
 * catalog, after running your real bootstrap script in a test database.
 *
 * <p>Every policy rests on a premise that lives in an initialisation script no test usually reads: the runtime role
 * is not a superuser, has no {@code BYPASSRLS}, is not a member of a role that has either (it could
 * {@code SET ROLE}), and owns no table whose RLS is not forced. Adding {@code BYPASSRLS} is the natural shortcut in
 * front of a query that "does not see its rows", and it switches every policy off without failing a single test.
 *
 * <p>Use it after executing your init script (the same one production runs) against the test database.
 */
public final class RuntimeRoleVerifier extends Guard<RuntimeRoleVerifier> {

    private final Catalog catalog;
    private final List<String> roles;

    private RuntimeRoleVerifier(DataSource dataSource, List<String> roles) {
        this.catalog = new Catalog(Objects.requireNonNull(dataSource, "dataSource"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("name at least one runtime role");
        }
        this.roles = List.copyOf(roles);
    }

    /** Checks the named roles; {@code dataSource} only needs to read the catalog. */
    public static RuntimeRoleVerifier of(DataSource dataSource, String... runtimeRoles) {
        return new RuntimeRoleVerifier(dataSource, List.of(runtimeRoles));
    }

    @Override
    protected String name() {
        return "RuntimeRoleVerifier";
    }

    @Override
    protected String rationale() {
        return "a runtime role that is superuser, BYPASSRLS, able to SET ROLE to one, or owner of a non-forced table "
                + "switches every policy off, and no integration test notices";
    }

    @Override
    protected String remedy() {
        return "create the role NOSUPERUSER NOBYPASSRLS, owning nothing, member of no privileged role "
                + "(see runtime-role.sql); run migrations as a different role";
    }

    @Override
    protected void evaluate(Collector collector) {
        for (String role : roles) {
            List<Object[]> rows = catalog.query("""
                    select r.rolsuper, r.rolbypassrls,
                           array(select p.rolname::text from pg_roles p
                                  where p.oid <> r.oid and (p.rolsuper or p.rolbypassrls)
                                    and pg_has_role(r.oid, p.oid, 'MEMBER') order by 1),
                           array(select c.oid::regclass::text from pg_class c
                                  where c.relowner = r.oid and c.relkind in ('r', 'p')
                                    and c.relrowsecurity and not c.relforcerowsecurity order by 1),
                           r.rolcreaterole
                      from pg_roles r where r.rolname = ?
                    """, rs -> new Object[] {rs.getBoolean(1), rs.getBoolean(2), texts(rs.getArray(3)),
                    texts(rs.getArray(4)), rs.getBoolean(5)}, role);
            if (rows.isEmpty()) {
                collector.violation(role, "role does not exist: the bootstrap script did not run, or the name "
                        + "changed");
                continue;
            }
            collector.examined();
            Object[] r = rows.get(0);
            List<String> problems = new ArrayList<>();
            if ((Boolean) r[0]) {
                problems.add("is a superuser");
            }
            if ((Boolean) r[1]) {
                problems.add("has BYPASSRLS");
            }
            if (!((List<?>) r[2]).isEmpty()) {
                problems.add("can SET ROLE to " + r[2] + ", which bypass RLS");
            }
            if (!((List<?>) r[3]).isEmpty()) {
                problems.add("owns " + r[3] + " whose RLS is not forced");
            }
            if ((Boolean) r[4]) {
                problems.add("has CREATEROLE: it can create and grant roles, a path out of RLS");
            }
            if (!problems.isEmpty()) {
                collector.violation(role, String.join("; ", problems));
            }
        }
    }

    private static List<String> texts(Array array) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
