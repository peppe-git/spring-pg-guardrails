// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.peppe_git.pgguardrails.guardrails.rules.AsyncExecutorDeclared;
import io.github.peppe_git.pgguardrails.guardrails.rules.EnumValuesMatchCheckConstraints;
import io.github.peppe_git.pgguardrails.guardrails.rules.InjectedClockConsistency;
import io.github.peppe_git.pgguardrails.guardrails.rules.JpaNullabilityMatchesSchema;
import io.github.peppe_git.pgguardrails.guardrails.rules.MigrationChecksumLedger;
import io.github.peppe_git.pgguardrails.guardrails.rules.NoRemoteCallInTransaction;
import io.github.peppe_git.pgguardrails.guardrails.rules.OnConflictHasArbiter;
import io.github.peppe_git.pgguardrails.guardrails.rules.UnambiguousComponentConstructors;
import io.github.peppe_git.pgguardrails.guardrails.support.ProductionClasses;
import io.github.peppe_git.pgguardrails.rlstesting.RlsCoverage;
import io.github.peppe_git.pgguardrails.rlstesting.RlsIsolation;
import io.github.peppe_git.pgguardrails.rlstesting.RuntimeRoleVerifier;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every guard of the library, on this application. This is the file to copy into your own project.
 */
@SpringBootTest
class GuardrailsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DemoDatabase.register(registry);
    }

    private static final JavaClasses APP = ProductionClasses.of(3, "io.github.peppe_git.pgguardrails.example");

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConfigurableApplicationContext context;

    // --- row-level security -------------------------------------------------------------------------------

    @Test
    void everyTenantTableHasForcedRlsAndSoundPolicies() {
        RlsCoverage.of(DemoDatabase.superuser())
                .exempt("public.accounts", "read at login, before the tenant is known; scoped by the application")
                .minimumExamined(3)
                .verify();
    }

    @Test
    void everyRlsTableIsIsolatedForARoleSubjectToRls() {
        RlsIsolation.of(DemoDatabase.superuser()).minimumExamined(2).verify();
    }

    @Test
    void theRuntimeRoleIsSubjectToRls() {
        RuntimeRoleVerifier.of(DemoDatabase.superuser(), "app_runtime").verify();
    }

    // --- Spring code ---------------------------------------------------------------------------------------

    @Test
    void noRemoteCallInsideATransaction() {
        NoRemoteCallInTransaction.in(APP).minimumExamined(4).verify();
    }

    @Test
    void classesWithAClockReadTimeOnlyFromIt() {
        InjectedClockConsistency.in(APP).verify();
    }

    @Test
    void componentsHaveAnUnambiguousConstructor() {
        UnambiguousComponentConstructors.in(APP).verify();
    }

    @Test
    void asyncWorkRunsOnAChosenExecutor() {
        AsyncExecutorDeclared.in(context).minimumExamined(10).verify();
    }

    // --- code and schema together ------------------------------------------------------------------------

    @Test
    void everyOnConflictHasAnArbiter() {
        OnConflictHasArbiter.in(dataSource, Path.of("src/main/java")).verify();
    }

    @Test
    void entitiesPromiseNoMoreThanTheSchemaEnforces() {
        JpaNullabilityMatchesSchema.in(emf, dataSource).minimumExamined(4).verify();
    }

    @Test
    void enumsAndCheckConstraintsAgree() {
        EnumValuesMatchCheckConstraints.in(emf, dataSource).verify();
    }

    @Test
    void appliedMigrationsNeverChange() {
        MigrationChecksumLedger.of(Path.of("src/main/resources/db/migration"),
                Path.of("src/test/resources/migrations.sha256")).minimumExamined(2).verify();
    }
}
