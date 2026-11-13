// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.pgguardrails.rls.bootapp.NoteService;
import io.github.peppe_git.pgguardrails.rls.bootapp.NotesApp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Boot auto-configuration with JPA: the listener lands on the {@link JpaTransactionManager}, Hibernate's
 * connection carries the tenant, and the runtime role check passes in {@code fail} mode because the application
 * connects as the runtime role.
 */
@SpringBootTest(classes = NotesApp.class)
class PgRlsAutoConfigurationIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestPostgres.RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> TestPostgres.RUNTIME_PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("pg-rls.runtime-role-check", () -> "fail");
    }

    @Autowired
    private NoteService notes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void theListenerIsRegisteredOnTheJpaTransactionManager() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getTransactionExecutionListeners())
                .hasAtLeastOneElementOfType(RlsTransactionListener.class);
    }

    @Test
    void jpaReadsAndWritesAsTheBoundTenant() {
        TenantContext.runAs(a, () -> notes.add("a"));
        TenantContext.runAs(b, () -> notes.add("b"));
        assertThat(TenantContext.callAs(a, () -> notes.countAmong(List.of(a, b)))).isEqualTo(1);
        assertThat(TenantContext.callAs(b, () -> notes.countAmong(List.of(a, b)))).isEqualTo(1);
    }

    /**
     * Strict mode raises when a policy is evaluated, i.e. on the first row the scan meets: over an empty table there
     * is nothing to hide and the statement returns zero rows. Hence the row inserted first.
     */
    @Test
    void strictModeIsOnByDefaultSoAForgottenTenantIsAnError() {
        TenantContext.runAs(a, () -> notes.add("a"));
        assertThatThrownBy(() -> notes.countAll())
                .satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).as("%s", e).isTrue());
    }

    @Test
    void theAdminReadEscapeWorksThroughJpaInReadOnlyTransactionsOnly() {
        TenantContext.runAs(a, () -> notes.add("a"));
        TenantContext.runAs(b, () -> notes.add("b"));
        assertThat(notes.countAmongAsPlatformAdmin(List.of(a, b))).isEqualTo(2);
        assertThatThrownBy(() -> notes.countAmongAsPlatformAdminInReadWrite(List.of(a, b)))
                .isInstanceOf(RlsUsageException.class);
    }

    @Test
    void failModeRefusesToStartAsASuperuser() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(NotesApp.class).properties(
                "spring.datasource.url=" + TestPostgres.jdbcUrl(),
                "spring.datasource.username=postgres",
                "spring.datasource.password=" + TestPostgres.SUPERUSER_PASSWORD,
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.main.web-application-type=none",
                "pg-rls.runtime-role-check=fail");
        assertThatThrownBy(app::run).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT subject to row-level security")
                .hasMessageContaining("superuser");
    }
}
