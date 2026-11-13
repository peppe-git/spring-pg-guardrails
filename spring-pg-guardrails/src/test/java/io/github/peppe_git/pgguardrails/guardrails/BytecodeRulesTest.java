// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.peppe_git.pgguardrails.guardrails.fixtures.clock.ClockFixtures;
import io.github.peppe_git.pgguardrails.guardrails.fixtures.ctor.CtorFixtures;
import io.github.peppe_git.pgguardrails.guardrails.fixtures.remote.RemoteFixtures;
import io.github.peppe_git.pgguardrails.guardrails.rules.InjectedClockConsistency;
import io.github.peppe_git.pgguardrails.guardrails.rules.NoRemoteCallInTransaction;
import io.github.peppe_git.pgguardrails.guardrails.rules.UnambiguousComponentConstructors;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import io.github.peppe_git.pgguardrails.guardrails.support.ProductionClasses;
import org.junit.jupiter.api.Test;

/** The bytecode rules on fixtures with known defects: each must see exactly the defects, and nothing else. */
class BytecodeRulesTest {

    private static JavaClasses fixtures(Class<?> holder) {
        return new ClassFileImporter().importPackages(holder.getPackageName());
    }

    @Test
    void noRemoteCallInTransactionFindsDirectHelperAndComposedCases() {
        GuardReport report = NoRemoteCallInTransaction.in(fixtures(RemoteFixtures.class)).check();
        assertThat(report.examined()).isEqualTo(4);
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                RemoteFixtures.DirectCall.class.getName() + "#fetch",
                RemoteFixtures.ThroughPrivateHelper.class.getName() + "#render",
                RemoteFixtures.ComposedAnnotation.class.getName() + "#run");
        assertThat(report.violations().get(0).message()).contains("at ");
    }

    @Test
    void noRemoteCallInTransactionAcceptsADeclaredClientAndNamedExemptions() {
        GuardReport report = NoRemoteCallInTransaction.in(fixtures(RemoteFixtures.class))
                .withoutDefaults()
                .remoteCall("java.lang.ProcessBuilder", "start")
                .exempt(RemoteFixtures.ThroughPrivateHelper.class.getName() + "#render", "renders a local file")
                .check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
    }

    @Test
    void injectedClockConsistencyFlagsOnlyTheClassThatHasAClockAndIgnoresIt() {
        GuardReport report = InjectedClockConsistency.in(fixtures(ClockFixtures.class)).check();
        assertThat(report.examined()).isEqualTo(3);
        assertThat(report.violations()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(ClockFixtures.Leaky.class.getName());
            assertThat(f.message()).contains("Instant.now()").contains("System.currentTimeMillis()")
                    .contains("Date.<init>()");
        });
    }

    @Test
    void unambiguousComponentConstructorsFlagsUndesignatedOverloadsOfComponentsOnly() {
        GuardReport report = UnambiguousComponentConstructors.in(fixtures(CtorFixtures.class)).check();
        assertThat(report.examined()).isEqualTo(4);
        assertThat(report.violations()).extracting(Finding::id).containsExactlyInAnyOrder(
                CtorFixtures.TwoConstructorsNoneDesignated.class.getName(),
                CtorFixtures.ConfigurationWithNoArgFallback.class.getName());
    }

    @Test
    void productionClassesRefusesAnEmptyImport() {
        assertThatThrownBy(() -> ProductionClasses.of("com.example.does.not.exist"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("imported 0 classes");
    }
}
