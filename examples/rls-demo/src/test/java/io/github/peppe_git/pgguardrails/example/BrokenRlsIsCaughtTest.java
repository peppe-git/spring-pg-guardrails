// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import io.github.peppe_git.pgguardrails.rlstesting.RlsCoverage;
import io.github.peppe_git.pgguardrails.rlstesting.RlsIsolation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * A realistic mistake — a new {@code billing} module whose migration was written by hand — and what the guards say
 * about it. Every integration test of the module would be green: Testcontainers connects as a superuser.
 *
 * <p>The mistakes, in {@code src/test/resources/broken/billing.sql}: the {@code invoices} table is enabled but not
 * FORCEd and its policy puts the admin escape inside a {@code FOR ALL} USING; {@code payments} got no RLS at all.
 *
 * <p>To see the red build a developer would see: {@code ./mvnw -pl examples/rls-demo verify -Ddemo.broken=true}.
 */
class BrokenRlsIsCaughtTest {

    @BeforeAll
    static void applyTheBrokenMigration() {
        DemoDatabase.url();
        DemoDatabase.execute("drop schema if exists billing cascade");
        DemoDatabase.execute(DemoDatabase.resource("/broken/billing.sql"));
    }

    @Test
    void coverageNamesEveryMistake() {
        GuardReport report = RlsCoverage.of(DemoDatabase.superuser()).schemas("billing").check();
        assertThat(report.isSatisfied()).isFalse();
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                "billing.invoices", "billing.invoices#invoices_isolation", "billing.payments");
    }

    @Test
    void isolationShowsTheCrossTenantDeleteThroughTheAdminEscape() {
        GuardReport report = RlsIsolation.of(DemoDatabase.superuser()).schemas("billing").check();
        assertThat(report.violations()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("billing.invoices");
            assertThat(f.message()).contains("admin read escape lets a DELETE remove 1 row(s) of tenant A");
        });
    }

    /** The same checks as a failing test, for the demonstration. Off by default. */
    @Test
    @EnabledIfSystemProperty(named = "demo.broken", matches = "true")
    void theBuildGoesRed() {
        RlsCoverage.of(DemoDatabase.superuser()).schemas("billing").verify();
    }
}
