// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting.fixtures.app;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Table {@code audit_entry} (derived name), not an RLS table. */
@Entity
public class AuditEntry {
    @Id
    Long id;
}
