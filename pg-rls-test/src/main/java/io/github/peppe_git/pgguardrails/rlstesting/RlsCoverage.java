// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Proves, from the catalog, that no tenant table was forgotten and that no policy was written in a shape that leaks.
 *
 * <p>The isolation test ({@link RlsIsolation}) shows the doors it tries are locked; this guard shows there is no
 * door nobody tried. Adding a tenant table without RLS is a silent error — no test fails, no runtime error, the rows
 * simply become readable by every tenant. Checked, for the given schemas:
 * <ol>
 *   <li>every table (partitioned tables and every partition included) with the tenant column has RLS enabled
 *       <b>and</b> forced — without {@code FORCE} the table owner skips the policies;
 *   <li>no table anywhere in the schemas has RLS enabled but not forced;
 *   <li>no table has RLS enabled and no policy (fail-closed for everyone: works in superuser tests, breaks in
 *       production);
 *   <li>every permissive policy on a tenant table uses the tenant setting: its {@code USING} for the commands it
 *       covers, and its <b>effective</b> {@code WITH CHECK} (PostgreSQL falls back to {@code USING} when it is
 *       omitted) for INSERT/UPDATE/ALL;
 *   <li>the admin read escape appears only in {@code FOR SELECT} policies. In a {@code FOR ALL} policy it also
 *       covers {@code DELETE} — which has no {@code WITH CHECK} — so a cross-tenant delete passes through it; and an
 *       omitted {@code WITH CHECK} would let the escape write.
 * </ol>
 *
 * <p>Ids: {@code schema.table} for the table checks, {@code schema.table#policy} for policies. Exempt a tenant table
 * that is deliberately outside RLS (e.g. read before the tenant is known) by its {@code schema.table} with the
 * reason; the exemption fails the guard the day the table gets RLS.
 */
public final class RlsCoverage extends Guard<RlsCoverage> {

    private final DataSource dataSource;
    private List<String> schemas = List.of("public");
    private String tenantColumn = "tenant_id";
    private final List<String> tenantMarkers = new ArrayList<>();
    private final List<String> adminMarkers = new ArrayList<>();

    private RlsCoverage(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        settings(RlsSettings.defaults());
    }

    /** Coverage of the {@code public} schema, tenant column {@code tenant_id}, default GUC names. */
    public static RlsCoverage of(DataSource dataSource) {
        return new RlsCoverage(dataSource);
    }

    public RlsCoverage schemas(String... names) {
        this.schemas = List.of(names);
        return this;
    }

    public RlsCoverage tenantColumn(String column) {
        this.tenantColumn = Objects.requireNonNull(column);
        return this;
    }

    /**
     * Resets the markers to those of {@code settings}: its tenant GUC and the library's {@code tenant_for_read} /
     * {@code tenant_for_write} functions; its admin GUC and {@code admin_read_enabled}.
     */
    public RlsCoverage settings(RlsSettings settings) {
        tenantMarkers.clear();
        tenantMarkers.addAll(List.of(settings.tenantGuc(), "tenant_for_read(", "tenant_for_write("));
        adminMarkers.clear();
        adminMarkers.addAll(List.of(settings.adminReadGuc(), "admin_read_enabled("));
        return this;
    }

    /** Text that, found in a policy expression, means "this compares with the tenant" (e.g. your own function). */
    public RlsCoverage tenantMarker(String text) {
        tenantMarkers.add(Objects.requireNonNull(text));
        return this;
    }

    /** Text that, found in a policy expression, means "this is an admin escape" (e.g. a legacy GUC name). */
    public RlsCoverage adminMarker(String text) {
        adminMarkers.add(Objects.requireNonNull(text));
        return this;
    }

    @Override
    protected String name() {
        return "RlsCoverage";
    }

    @Override
    protected String rationale() {
        return "a tenant table without forced RLS, a policy without the tenant setting, or an admin escape outside "
                + "FOR SELECT leaks rows across tenants without any test or runtime error";
    }

    @Override
    protected String remedy() {
        return "protect the table with rls.enable_tenant_rls(...) (or ENABLE + FORCE ROW LEVEL SECURITY and policies "
                + "on the tenant setting); keep the admin escape in a FOR SELECT policy; exempt a deliberate exception "
                + "by its id with the reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        RlsCatalog catalog = new RlsCatalog(dataSource, schemas);
        List<RlsCatalog.Table> tables = catalog.tables(tenantColumn);
        Map<String, RlsCatalog.Table> byId = tables.stream()
                .collect(Collectors.toMap(RlsCatalog.Table::id, Function.identity()));
        Map<String, List<RlsCatalog.Policy>> policies = catalog.policies().stream()
                .collect(Collectors.groupingBy(RlsCatalog.Policy::table));

        for (RlsCatalog.Table t : tables) {
            if (t.hasTenantColumn()) {
                collector.examined();
                if (!t.rlsEnabled()) {
                    collector.violation(t.id(), "has " + tenantColumn + " but row-level security is not enabled"
                            + (t.partition() ? " (a partition: direct access skips the parent's policies)" : ""));
                } else if (!t.rlsForced()) {
                    collector.violation(t.id(), "RLS enabled but not FORCEd: owner '" + t.owner()
                            + "' skips every policy");
                }
            } else if (t.rlsEnabled() && !t.rlsForced()) {
                collector.violation(t.id(), "RLS enabled but not FORCEd: owner '" + t.owner()
                        + "' skips every policy");
            }
            if (t.rlsEnabled() && policies.getOrDefault(t.id(), List.of()).isEmpty()) {
                collector.violation(t.id() + "#no-policy", "RLS enabled and no policy: nobody but a superuser sees "
                        + "a row (green in superuser tests, broken in production)");
            }
        }

        policies.forEach((tableId, list) -> {
            RlsCatalog.Table t = byId.get(tableId);
            if (t == null || !t.hasTenantColumn()) {
                return;
            }
            for (RlsCatalog.Policy p : list) {
                collector.examined();
                if (!p.permissive()) {
                    continue;
                }
                inspect(collector, p);
            }
        });
    }

    private void inspect(Collector collector, RlsCatalog.Policy p) {
        List<String> problems = new ArrayList<>();
        String using = p.using();
        String check = p.effectiveWithCheck();
        switch (p.command()) {
            case "r" -> {
                if (!has(using, tenantMarkers) && !has(using, adminMarkers)) {
                    problems.add("its USING uses neither the tenant setting nor the admin read escape");
                }
            }
            case "a" -> requireTenantNoAdmin("WITH CHECK", check, problems);
            case "w" -> {
                requireTenantNoAdmin("USING", using, problems);
                requireTenantNoAdmin(p.withCheck() == null ? "WITH CHECK (inherited from USING)" : "WITH CHECK",
                        check, problems);
            }
            case "d" -> requireTenantNoAdmin("USING", using, problems);
            case "*" -> {
                requireTenantNoAdmin("USING (also applied to DELETE, which has no WITH CHECK)", using, problems);
                requireTenantNoAdmin(p.withCheck() == null ? "WITH CHECK (inherited from USING)" : "WITH CHECK",
                        check, problems);
            }
            default -> problems.add("unknown command " + p.command());
        }
        if (!problems.isEmpty()) {
            collector.violation(p.id(), command(p.command()) + " policy: " + String.join("; ", problems));
        }
    }

    private void requireTenantNoAdmin(String clause, String expression, List<String> problems) {
        if (expression == null || !has(expression, tenantMarkers)) {
            problems.add(clause + " does not use the tenant setting");
        }
        if (expression != null && has(expression, adminMarkers)) {
            problems.add(clause + " contains the admin escape, which must stay in a FOR SELECT policy");
        }
    }

    private static boolean has(String expression, List<String> markers) {
        return expression != null && markers.stream().anyMatch(expression::contains);
    }

    private static String command(String code) {
        return switch (code) {
            case "r" -> "FOR SELECT";
            case "a" -> "FOR INSERT";
            case "w" -> "FOR UPDATE";
            case "d" -> "FOR DELETE";
            default -> "FOR ALL";
        };
    }
}
