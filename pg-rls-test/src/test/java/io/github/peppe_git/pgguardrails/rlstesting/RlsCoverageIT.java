// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import org.junit.jupiter.api.Test;

class RlsCoverageIT {

    @Test
    void aCorrectSchemaPassesOnceTheDeliberateExceptionIsDeclared() {
        GuardReport report = RlsCoverage.of(TestDatabase.superuser())
                .exempt("public.users", "read during login, before the tenant is known; scoped by the application")
                .check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
        // 5 tenant tables (3 + the default partition + users) and 4 policies x 4 tables
        assertThat(report.examined()).isEqualTo(5 + 16);
    }

    @Test
    void aTenantTableOutsideRlsIsAViolationUntilDeclared() {
        GuardReport report = RlsCoverage.of(TestDatabase.superuser()).check();
        assertThat(report.violations()).extracting(Finding::id).containsExactly("public.users");
    }

    @Test
    void anExemptionForATableThatIsNowProtectedIsStale() {
        GuardReport report = RlsCoverage.of(TestDatabase.superuser())
                .exempt("public.users", "read during login")
                .exempt("public.good_tasks", "copied from an old list")
                .check();
        assertThat(report.staleExemptions()).containsExactly("public.good_tasks");
        assertThat(report.isSatisfied()).isFalse();
    }

    @Test
    void everyKnownWayOfGettingItWrongIsCaught() {
        GuardReport report = RlsCoverage.of(TestDatabase.superuser()).schemas("broken").check();
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                "broken.no_rls",
                "broken.not_forced",
                "broken.no_policy#no-policy",
                "broken.legacy_admin#p",
                "broken.open_check#p",
                "broken.leaky_read#p_read");
        assertThat(report.violations()).filteredOn(f -> f.id().equals("broken.legacy_admin#p")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("admin escape").contains("DELETE"));
        assertThat(report.violations()).filteredOn(f -> f.id().equals("broken.open_check#p")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("WITH CHECK does not use the tenant setting"));
    }

    @Test
    void theInlineTemplateOfTheLibraryPassesToo() {
        TestDatabase.execute("""
                create schema if not exists inline_ok;
                drop table if exists inline_ok.items;
                create table inline_ok.items (tenant_id uuid not null, v text);
                """);
        TestDatabase.execute(io.github.peppe_git.pgguardrails.rls.RlsSql.render(
                io.github.peppe_git.pgguardrails.rls.RlsSql.INLINE_POLICY, java.util.Map.of(
                        "table", "inline_ok.items", "tenant_column", "tenant_id", "tenant_type", "uuid",
                        "tenant_guc", "app.current_tenant", "admin_read_guc", "app.rls_admin_read"))
                .replace("inline_ok.items_tenant_isolation", "items_tenant_isolation")
                .replace("inline_ok.items_admin_read", "items_admin_read"));
        GuardReport report = RlsCoverage.of(TestDatabase.superuser()).schemas("inline_ok").check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
    }
}
