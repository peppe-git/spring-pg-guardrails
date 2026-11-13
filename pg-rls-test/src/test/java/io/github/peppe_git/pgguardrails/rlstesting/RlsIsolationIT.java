// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import org.junit.jupiter.api.Test;

class RlsIsolationIT {

    @Test
    void everyRlsTableOfACorrectSchemaIsIsolatedWithGeneratedRows() {
        GuardReport report = RlsIsolation.of(TestDatabase.superuser()).minimumExamined(3).check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
        assertThat(report.examined()).as("good_projects, good_tasks, good_events").isEqualTo(3);
    }

    @Test
    void leaksAreFoundByExercisingThePoliciesNotByReadingThem() {
        GuardReport report = RlsIsolation.of(TestDatabase.superuser()).schemas("broken").check();
        assertThat(report.violations()).as(report.describe()).extracting(Finding::id).containsExactlyInAnyOrder(
                "broken.no_policy", "broken.legacy_admin", "broken.open_check", "broken.leaky_read");
        assertThat(message(report, "broken.no_policy")).contains("cannot read its own row");
        assertThat(message(report, "broken.legacy_admin")).contains("lets a DELETE remove 1 row");
        assertThat(message(report, "broken.open_check")).contains("inserts a row that belongs to tenant A");
        assertThat(message(report, "broken.leaky_read")).contains("tenant B sees");
    }

    @Test
    void aTableTheGeneratorCannotFillIsInconclusiveUntilItGetsAFixture() {
        GuardReport without = RlsIsolation.of(TestDatabase.superuser()).schemas("needs_fixture").check();
        assertThat(without.isSatisfied()).isFalse();
        assertThat(without.inconclusive()).extracting(Finding::id).containsExactly("needs_fixture.codes");

        GuardReport with = RlsIsolation.of(TestDatabase.superuser()).schemas("needs_fixture")
                .fixture("needs_fixture.codes", "insert into needs_fixture.codes values (:tenant, 'abcde')")
                .check();
        assertThat(with.isSatisfied()).as(with.describe()).isTrue();
    }

    @Test
    void theGuardLeavesNothingBehind() {
        RlsIsolation.of(TestDatabase.superuser()).check();
        RlsProbe probe = RlsProbe.on(TestDatabase.superuser());
        assertThat(probe.countWithoutTenant("select count(*) from pg_roles where rolname = 'rls_isolation_probe'"))
                .isZero();
        try (var con = TestDatabase.superuser().getConnection(); var st = con.createStatement();
                var rs = st.executeQuery("select count(*) from good_projects")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String message(GuardReport report, String id) {
        return report.violations().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow().message();
    }
}
