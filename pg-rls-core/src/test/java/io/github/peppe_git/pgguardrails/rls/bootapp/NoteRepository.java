// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.bootapp;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {

    long countByTenantIdIn(Collection<UUID> tenants);
}
