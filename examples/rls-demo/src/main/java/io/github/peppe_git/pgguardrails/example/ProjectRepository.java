// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByStatusOrderByName(Project.Status status);

    /** Idempotent: adding the same member twice is a no-op, decided atomically by the unique index. */
    @Modifying
    @Query(nativeQuery = true, value = "insert into project_members (tenant_id, project_id, member_email) "
            + "values (:tenant, :project, :email) on conflict (project_id, member_email) do nothing")
    int addMember(UUID tenant, long project, String email);

    @Query(nativeQuery = true, value = "select count(*) from project_members where project_id = :project")
    long countMembers(long project);
}
