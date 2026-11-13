// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import io.github.peppe_git.pgguardrails.rls.TenantContext;
import io.github.peppe_git.pgguardrails.rls.TenantGuc;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * No method here mentions the tenant setting: the tenant bound by {@code TenantContext} when the transaction starts
 * is written into it by pg-rls-core, and PostgreSQL does the filtering. Forgetting to bind it is an error (strict
 * mode), not an empty list.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final TenantGuc tenantGuc;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, TenantGuc tenantGuc, Clock clock) {
        this.projects = projects;
        this.tenantGuc = tenantGuc;
        this.clock = clock;
    }

    @Transactional
    public Project create(String name) {
        UUID tenant = TenantContext.current(UUID.class).orElseThrow();
        return projects.save(new Project(tenant, name, Instant.now(clock)));
    }

    @Transactional
    public boolean addMember(long projectId, String email) {
        UUID tenant = TenantContext.current(UUID.class).orElseThrow();
        return projects.addMember(tenant, projectId, email) == 1;
    }

    @Transactional(readOnly = true)
    public List<Project> openProjects() {
        return projects.findByStatusOrderByName(Project.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public long members(long projectId) {
        return projects.countMembers(projectId);
    }

    /** Platform support view: every tenant, read-only. Call only after authorising a platform administrator. */
    @Transactional(readOnly = true)
    public long countAllProjectsForSupport() {
        tenantGuc.applyAdminRead();
        return projects.count();
    }
}
