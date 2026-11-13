// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting.fixtures.app;

import io.github.peppe_git.pgguardrails.rls.TenantContext;
import io.github.peppe_git.pgguardrails.rls.TenantGuc;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Two correct services, three that forget the GUC, one with a custom wrapper, one without tenant data. */
public final class Services {

    private Services() {
    }

    public static class AppliesFirst {
        private TenantGuc guc;
        private Repositories.NoteRepository notes;

        @Transactional
        public long count(UUID tenant) {
            guc.apply(tenant);
            return notes.count();
        }
    }

    public static class AppliesThroughAHelper {
        private Repositories.OtherNoteRepository notes;

        @Transactional
        public long count(UUID tenant) {
            return TenantContext.callAs(tenant, this::countAll);
        }

        private long countAll() {
            return notes.count();
        }
    }

    public static class Forgets {
        private Repositories.NoteRepository notes;

        @Transactional
        public long count() {
            return notes.count();
        }
    }

    public static class ForgetsThroughIntermediateRepository {
        private Repositories.OtherNoteRepository notes;

        @Transactional
        public long count() {
            return notes.count();
        }
    }

    public static class ForgetsWithATemplate {
        private TransactionTemplate tx;
        private Repositories.NoteRepository notes;

        public Long count() {
            return tx.execute(s -> notes.count());
        }
    }

    /** A project-specific wrapper that sets the GUC after an authorisation check. */
    public static class TenantScope {
        private TenantGuc guc;

        public void enter(UUID tenant) {
            guc.apply(tenant);
        }
    }

    public static class AppliesThroughACustomWrapper {
        private TenantScope scope;
        private Repositories.NoteRepository notes;

        @Transactional
        public long count(UUID tenant) {
            scope.enter(tenant);
            return notes.count();
        }
    }

    public static class NotTenantData {
        private Repositories.AuditRepository audit;

        @Transactional
        public long count() {
            return audit.count();
        }
    }
}
