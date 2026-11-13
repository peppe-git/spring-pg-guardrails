// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting.fixtures.app;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "rt_notes")
public class Note {
    @Id
    Long id;
    UUID tenantId;
}
