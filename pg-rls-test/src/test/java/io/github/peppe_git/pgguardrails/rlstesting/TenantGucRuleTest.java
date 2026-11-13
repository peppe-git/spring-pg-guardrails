// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import io.github.peppe_git.pgguardrails.rlstesting.fixtures.app.Services;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantGucRuleTest {

    private static final JavaClasses APP = new ClassFileImporter().importPackages(
            Services.class.getPackageName());

    @Test
    void transactionsThatReachATenantRepositoryWithoutTheGucAreViolations() {
        GuardReport report = TenantGucRule.in(APP, Set.of("rt_notes")).check();
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                Services.Forgets.class.getName() + "#count",
                Services.ForgetsThroughIntermediateRepository.class.getName() + "#count",
                Services.ForgetsWithATemplate.class.getName() + "#count",
                Services.AppliesThroughACustomWrapper.class.getName() + "#count");
        assertThat(report.examined()).as("six methods reach rt_notes in a transaction").isEqualTo(6);
    }

    @Test
    void aCustomApplierCountsAsSettingTheGuc() {
        GuardReport report = TenantGucRule.in(APP, Set.of("rt_notes"))
                .applier(Services.TenantScope.class.getName(), "enter")
                .check();
        assertThat(report.violations()).extracting(Finding::id)
                .doesNotContain(Services.AppliesThroughACustomWrapper.class.getName() + "#count")
                .hasSize(3);
    }

    @Test
    void tablesThatAreNotRlsTablesAreOutOfScope() {
        GuardReport report = TenantGucRule.in(APP, Set.of("audit_entry")).check();
        assertThat(report.violations()).extracting(Finding::id)
                .containsExactly(Services.NotTenantData.class.getName() + "#count");
    }
}
