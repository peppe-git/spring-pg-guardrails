// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.rules.OnConflictHasArbiter;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnConflictHasArbiterIT {

    @BeforeAll
    static void schema() {
        TestPostgres.execute("""
                drop table if exists oc_accounts, oc_events, oc_plain;
                create table oc_accounts (id bigint primary key, email text not null, tenant uuid not null,
                                          deleted_at timestamptz,
                                          constraint oc_accounts_email_tenant unique (tenant, email));
                create table oc_events (id bigint, source text, ref text);
                create unique index oc_events_live on oc_events (source, ref) where id is not null;
                create table oc_plain (v text);
                """);
    }

    @TempDir
    Path sources;

    private GuardReport check(String java) throws IOException {
        Path pkg = Files.createDirectories(sources.resolve("com/acme"));
        Files.writeString(pkg.resolve("Repo.java"), java);
        return OnConflictHasArbiter.in(TestPostgres.dataSource(), sources).relativeTo(sources).check();
    }

    @Test
    void targetsThatMatchAUniqueIndexOrConstraintPass() throws IOException {
        GuardReport report = check("""
                class Repo {
                    String a = "insert into oc_accounts (id, email, tenant) values (?, ?, ?) "
                            + "on conflict (email, tenant) do nothing";
                    String b = \"""
                        INSERT INTO oc_accounts (id, email, tenant) VALUES (?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET email = excluded.email
                        \""";
                    String c = "insert into oc_accounts values (?, ?, ?) on conflict on constraint oc_accounts_email_tenant do nothing";
                    String d = "insert into oc_events values (?, ?, ?) on conflict (source, ref) where id is not null do nothing";
                    // insert into oc_plain values (?) on conflict (v) do nothing   <- a comment, not code
                }
                """);
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
        assertThat(report.examined()).isEqualTo(4);
    }

    @Test
    void targetsWithoutArbiterAreViolations() throws IOException {
        GuardReport report = check("""
                class Repo {
                    String wrongColumns = "insert into oc_accounts (id, email) values (?, ?) on conflict (email) do nothing";
                    String partialWithoutWhere = "insert into oc_events values (?, ?, ?) on conflict (source, ref) do nothing";
                    String missingConstraint = "insert into oc_accounts values (?) on conflict on constraint nope do nothing";
                    String noArbiterAtAll = "insert into oc_plain values (?) on conflict do nothing";
                    String noSuchTable = "insert into oc_missing values (?) on conflict (v) do nothing";
                }
                """);
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                "com/acme/Repo.java#oc_accounts(email)",
                "com/acme/Repo.java#oc_events(source, ref)",
                "com/acme/Repo.java#oc_accounts(nope)",
                "com/acme/Repo.java#oc_plain()",
                "com/acme/Repo.java#oc_missing(v)");
        assertThat(report.violations()).filteredOn(f -> f.id().endsWith("oc_events(source, ref)"))
                .singleElement().satisfies(f -> assertThat(f.message()).contains("partial index"));
    }

    @Test
    void anExpressionTargetIsInconclusiveNotGreen() throws IOException {
        GuardReport report = check("""
                class Repo {
                    String s = "insert into oc_accounts values (?) on conflict (lower(email)) do nothing";
                }
                """);
        assertThat(report.isSatisfied()).isFalse();
        assertThat(report.inconclusive()).hasSize(1);
    }
}
