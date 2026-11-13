// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.pgguardrails.rls.RlsErrors;
import io.github.peppe_git.pgguardrails.rls.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** What the application code sees: plain JPA, and the database keeps tenants apart. */
@SpringBootTest
class TenantIsolationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DemoDatabase.register(registry);
    }

    @Autowired
    private ProjectService projects;

    private final UUID north = UUID.randomUUID();
    private final UUID south = UUID.randomUUID();

    @Test
    void eachTenantSeesOnlyItsOwnProjects() {
        TenantContext.runAs(north, () -> projects.create("Roadmap"));
        TenantContext.runAs(south, () -> projects.create("Payroll"));

        assertThat(TenantContext.callAs(north, projects::openProjects)).extracting(Project::getName)
                .containsExactly("Roadmap");
        assertThat(TenantContext.callAs(south, projects::openProjects)).extracting(Project::getName)
                .containsExactly("Payroll");
    }

    @Test
    void addingAMemberTwiceIsIdempotentThanksToTheArbiter() {
        long id = TenantContext.callAs(north, () -> projects.create("Hiring").getId());
        assertThat(TenantContext.callAs(north, () -> projects.addMember(id, "member-1"))).isTrue();
        assertThat(TenantContext.callAs(north, () -> projects.addMember(id, "member-1"))).isFalse();
        assertThat(TenantContext.callAs(north, () -> projects.members(id))).isEqualTo(1);
    }

    @Test
    void forgettingTheTenantIsAnErrorNotAnEmptyList() {
        TenantContext.runAs(north, () -> projects.create("Budget"));
        assertThatThrownBy(projects::openProjects)
                .satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).as("%s", e).isTrue());
    }

    @Test
    void theSupportViewReadsEveryTenantThroughTheReadOnlyEscape() {
        TenantContext.runAs(north, () -> projects.create("A"));
        TenantContext.runAs(south, () -> projects.create("B"));
        assertThat(projects.countAllProjectsForSupport()).isGreaterThanOrEqualTo(2);
    }
}
