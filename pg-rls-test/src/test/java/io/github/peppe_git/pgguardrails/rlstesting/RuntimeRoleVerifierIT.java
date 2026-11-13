// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import io.github.peppe_git.pgguardrails.rls.RlsSql;
import org.junit.jupiter.api.Test;

class RuntimeRoleVerifierIT {

    @Test
    void theRoleCreatedByTheShippedScriptIsSubjectToRls() {
        TestDatabase.execute(RlsSql.runtimeRole("app_runtime_ok", RlsSql.DEFAULT_SCHEMA));
        GuardReport report = RuntimeRoleVerifier.of(TestDatabase.superuser(), "app_runtime_ok").check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
    }

    @Test
    void theShortcutsThatSwitchRlsOffAreCaught() {
        TestDatabase.execute("""
                do $$ begin
                  if not exists (select 1 from pg_roles where rolname = 'rt_bypass') then
                    create role rt_bypass login bypassrls;
                  end if;
                  if not exists (select 1 from pg_roles where rolname = 'rt_admin_group') then
                    create role rt_admin_group nologin bypassrls;
                  end if;
                  if not exists (select 1 from pg_roles where rolname = 'rt_member') then
                    create role rt_member login;
                  end if;
                  if not exists (select 1 from pg_roles where rolname = 'rt_owner') then
                    create role rt_owner login;
                  end if;
                end $$;
                grant rt_admin_group to rt_member;
                create schema if not exists rt;
                drop table if exists rt.rt_owned;
                create table rt.rt_owned (tenant_id uuid);
                alter table rt.rt_owned owner to rt_owner;
                alter table rt.rt_owned enable row level security;
                """);
        GuardReport report = RuntimeRoleVerifier.of(TestDatabase.superuser(),
                "rt_bypass", "rt_member", "rt_owner", "rt_missing").check();
        assertThat(report.violations()).extracting(Finding::id)
                .containsExactlyInAnyOrder("rt_bypass", "rt_member", "rt_owner", "rt_missing");
        assertThat(report.violations()).filteredOn(f -> f.id().equals("rt_member")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("SET ROLE").contains("rt_admin_group"));
        assertThat(report.violations()).filteredOn(f -> f.id().equals("rt_owner")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("rt_owned"));
    }
}
