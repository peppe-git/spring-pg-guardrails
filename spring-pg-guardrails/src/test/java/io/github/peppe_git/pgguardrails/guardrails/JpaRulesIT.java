// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.fixtures.jpa.Customer;
import io.github.peppe_git.pgguardrails.guardrails.rules.EnumValuesMatchCheckConstraints;
import io.github.peppe_git.pgguardrails.guardrails.rules.JpaNullabilityMatchesSchema;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** The two JPA rules against a real catalog, with an entity that carries known defects. */
class JpaRulesIT {

    private static DataSource dataSource;
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManagerFactory emf;

    @BeforeAll
    static void schemaAndEntityManagerFactory() {
        dataSource = TestPostgres.dataSource();
        TestPostgres.execute("""
                drop table if exists jpa_customers;
                drop type if exists jpa_mood;
                create type jpa_mood as enum ('HAPPY', 'SAD');
                create table jpa_customers (
                    id bigint primary key,
                    email text not null,
                    nickname text,
                    login_count int,
                    status varchar(20) not null check (status in ('ACTIVE', 'SUSPENDED')),
                    tier varchar(20) check (tier in ('FREE', 'GOLD', 'LEGACY')),
                    mood jpa_mood,
                    status_ordinal smallint
                );
                """);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(Customer.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        emf = factory.getObject();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @Test
    void nullabilityPromisesTheSchemaDoesNotKeepAreViolations() {
        GuardReport report = JpaNullabilityMatchesSchema.in(emf, dataSource).check();
        assertThat(report.violations()).extracting(Finding::id)
                .containsExactlyInAnyOrder("jpa_customers.nickname", "jpa_customers.login_count");
        assertThat(report.violations()).filteredOn(f -> f.id().endsWith("login_count")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("primitive"));
        assertThat(report.examined()).as("every mapped column but the id").isEqualTo(7);
        assertThat(report.inconclusive()).isEmpty();
    }

    @Test
    void enumsThatDisagreeWithTheirConstraintAreViolationsInBothDirections() {
        GuardReport report = EnumValuesMatchCheckConstraints.in(emf, dataSource).check();
        assertThat(report.violations()).as(report.describe()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("jpa_customers.tier");
            assertThat(f.message()).contains("rejects [PLATINUM]").contains("lacks [LEGACY]");
        });
        assertThat(report.examined()).as("status (CHECK), tier (CHECK), mood (enum type)").isEqualTo(3);
        assertThat(report.notes()).anySatisfy(n -> assertThat(n).contains("status_ordinal").contains("ordinal"));
    }

    @Test
    void exemptingTheKnownDefectsMakesBothGreen() {
        assertThat(JpaNullabilityMatchesSchema.in(emf, dataSource)
                .exempt("jpa_customers.nickname", "backfill pending, NOT NULL in the next migration")
                .exempt("jpa_customers.login_count", "legacy rows, column defaulted next release")
                .check().isSatisfied()).isTrue();
        assertThat(EnumValuesMatchCheckConstraints.in(emf, dataSource)
                .exempt("jpa_customers.tier", "PLATINUM ships behind a flag with its migration")
                .check().isSatisfied()).isTrue();
    }
}
