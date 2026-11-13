// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @AfterEach
    void threadIsClean() {
        assertThat(TenantContext.current()).as("a scope leaked a tenant onto the test thread").isEmpty();
    }

    @Test
    void callAsBindsTheTenantAndRemovesItAfterwards() {
        Object seen = TenantContext.callAs(a, TenantContext::require);
        assertThat(seen).isEqualTo(a);
    }

    @Test
    void nestedScopesRestoreThePreviousTenantNotNothing() {
        TenantContext.runAs(a, () -> {
            TenantContext.runAs(b, () -> assertThat(TenantContext.require()).isEqualTo(b));
            assertThat(TenantContext.require()).isEqualTo(a);
        });
    }

    @Test
    void theTenantIsRestoredWhenTheWorkThrowsAndTheCheckedExceptionKeepsItsType() {
        assertThatThrownBy(() -> TenantContext.runAs(a, () -> {
            throw new IOException("boom");
        })).isInstanceOf(IOException.class).hasMessage("boom");
    }

    @Test
    void requireFailsLoudlyWithoutATenant() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(RlsUsageException.class)
                .hasMessageContaining("no tenant is bound");
    }

    @Test
    void nullAndBlankTenantsAreRejectedBecauseTheyWouldReadAsNoTenant() {
        assertThatThrownBy(() -> TenantContext.callAs(null, () -> 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.callAs("  ", () -> 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typedAccessRefusesAWrongType() {
        TenantContext.runAs(a, () -> {
            assertThat(TenantContext.current(UUID.class)).contains(a);
            assertThatThrownBy(() -> TenantContext.current(Long.class)).isInstanceOf(ClassCastException.class);
        });
    }

    @Test
    void wrapCarriesTheTenantToAnotherThreadAndLeavesThatThreadClean() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Object> inside = new AtomicReference<>();
            Runnable task = TenantContext.callAs(a, () -> TenantContext.wrap(
                    (Runnable) () -> inside.set(TenantContext.current().orElse(null))));
            pool.submit(task).get();
            assertThat(inside.get()).isEqualTo(a);
            assertThat(pool.submit(() -> TenantContext.current().orElse(null)).get())
                    .as("the pooled thread kept the tenant after the task").isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTaskWrappedWithoutTenantHidesWhateverTheExecutingThreadCarries() throws Exception {
        Runnable wrapped = TenantContext.wrap((Runnable) () -> assertThat(TenantContext.current()).isEmpty());
        TenantContext.runAs(a, wrapped::run);
    }

    @Test
    void theTaskDecoratorPropagatesTheSubmitterTenant() {
        Runnable decorated = TenantContext.callAs(b, () -> new TenantContextTaskDecorator()
                .decorate(() -> assertThat(TenantContext.require()).isEqualTo(b)));
        decorated.run();
    }

    @Test
    void switchingTenantInsideATransactionTheLibraryDoesNotTrackIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> TenantContext.runAs(a, () -> { }))
                    .isInstanceOf(RlsUsageException.class)
                    .hasMessageContaining("does not track");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
