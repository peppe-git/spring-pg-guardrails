// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting.fixtures.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

public final class Repositories {

    private Repositories() {
    }

    public interface NoteRepository extends JpaRepository<Note, Long> {
    }

    /** An intermediate interface, as some code bases have. */
    public interface BaseRepository<T> extends CrudRepository<T, Long> {
    }

    public interface OtherNoteRepository extends BaseRepository<Note> {
    }

    public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    }
}
