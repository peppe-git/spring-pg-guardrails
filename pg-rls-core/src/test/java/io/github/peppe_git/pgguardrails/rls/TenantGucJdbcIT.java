// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The runtime with a plain {@link JdbcTransactionManager}, wired by hand (no Spring Boot), as the runtime role and
 * over a pool of ONE connection: every transaction reuses the same physical connection, so a setting that outlived
 * its transaction would show up in the next one.
 */
class TenantGucJdbcIT {

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TenantGuc guc;
    private static TransactionTemplate tx;
    private static TransactionTemplate readOnlyTx;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @BeforeAll
    static void wire() {
        TestPostgres.start();
        dataSource = TestPostgres.runtimePool(1);
        jdbc = new JdbcTemplate(dataSource);
        guc = new TenantGuc(dataSource, RlsSettings.builder().strict(false).build());
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        manager.addListener(new RlsTransactionListener(guc));
        tx = new TransactionTemplate(manager);
        readOnlyTx = new TransactionTemplate(manager);
        readOnlyTx.setReadOnly(true);
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    private long visibleNotes() {
        return jdbc.queryForObject("select count(*) from notes where tenant_id in (?, ?)", Long.class, a, b);
    }

    private void insertNote(UUID tenant) {
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s ->
                jdbc.update("insert into notes(tenant_id, body) values (?, 'x')", tenant)));
    }

    @Test
    void aTransactionStartedInsideAScopeCarriesTheTenant() {
        insertNote(a);
        insertNote(b);
        Long seen = TenantContext.callAs(a, () -> tx.execute(s -> visibleNotes()));
        assertThat(seen).isEqualTo(1);
        String guc = TenantContext.callAs(a, () -> tx.execute(s ->
                jdbc.queryForObject("select current_setting('app.current_tenant', true)", String.class)));
        assertThat(guc).isEqualTo(a.toString());
    }

    @Test
    void theSettingDoesNotOutliveItsTransactionOnAPooledConnection() {
        TenantContext.runAs(a, () -> tx.executeWithoutResult(s -> visibleNotes()));
        String after = tx.execute(s ->
                jdbc.queryForObject("select current_setting('app.current_tenant', true)", String.class));
        assertThat(after).as("same physical connection, next transaction").isNullOrEmpty();
    }

    @Test
    void aScopeOpenedInsideATransactionSwitchesTheSettingAndRestoresIt() {
        insertNote(a);
        insertNote(b);
        TenantContext.runAs(a, () -> tx.executeWithoutResult(s -> {
            assertThat(guc.currentGucValue()).isEqualTo(a.toString());
            Long seenAsB = TenantContext.callAs(b, () -> {
                assertThat(guc.currentGucValue()).isEqualTo(b.toString());
                return jdbc.queryForObject("select count(*) from notes where tenant_id = ?", Long.class, b);
            });
            assertThat(seenAsB).isEqualTo(1);
            assertThat(guc.currentGucValue()).as("restored after the inner scope").isEqualTo(a.toString());
            assertThat(TenantContext.require()).isEqualTo(a);
        }));
    }

    @Test
    void tenantGucRunAsWorksInATransactionThatStartedWithoutTenant() {
        insertNote(b);
        tx.executeWithoutResult(s -> {
            assertThat(guc.currentGucValue()).isNullOrEmpty();
            Long seen = guc.runAs(b, () ->
                    jdbc.queryForObject("select count(*) from notes where tenant_id = ?", Long.class, b));
            assertThat(seen).isEqualTo(1);
            assertThat(guc.currentGucValue()).isNullOrEmpty();
        });
    }

    @Test
    void aRequiresNewTransactionGetsItsOwnSettingAndTheOuterOneIsTrackedAgainAfterwards() {
        // REQUIRES_NEW needs a second connection while the first is held.
        try (HikariDataSource two = TestPostgres.runtimePool(2)) {
            TenantGuc g = new TenantGuc(two, RlsSettings.builder().strict(false).build());
            JdbcTransactionManager m = new JdbcTransactionManager(two);
            m.addListener(new RlsTransactionListener(g));
            TransactionTemplate outerTx = new TransactionTemplate(m);
            TransactionTemplate innerTx = new TransactionTemplate(m);
            innerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TenantContext.runAs(a, () -> outerTx.executeWithoutResult(outer -> {
                String inner = TenantContext.callAs(b, () -> innerTx.execute(s -> g.currentGucValue()));
                assertThat(inner).isEqualTo(b.toString());
                assertThat(g.currentGucValue()).isEqualTo(a.toString());
                TenantContext.runAs(b, () -> assertThat(g.currentGucValue()).isEqualTo(b.toString()));
                assertThat(g.currentGucValue()).isEqualTo(a.toString());
            }));
        }
    }

    @Test
    void writingTheSettingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> guc.apply(a)).isInstanceOf(RlsUsageException.class)
                .hasMessageContaining("open Spring transaction");
    }

    @Test
    void writingTheSettingOnAnAutoCommitConnectionIsRefused() {
        tx.executeWithoutResult(s -> {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try {
                con.setAutoCommit(true);
                assertThatThrownBy(() -> guc.apply(a)).isInstanceOf(RlsUsageException.class)
                        .hasMessageContaining("auto-commit");
                con.setAutoCommit(false);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void theAdminReadEscapeNeedsAReadOnlyTransaction() {
        insertNote(a);
        insertNote(b);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> guc.applyAdminRead()))
                .isInstanceOf(RlsUsageException.class).hasMessageContaining("read-only");
        Long all = readOnlyTx.execute(s -> {
            guc.applyAdminRead();
            return visibleNotes();
        });
        assertThat(all).isEqualTo(2);
    }

    @Test
    void strictModeMakesAForgottenTenantAnError() {
        try (HikariDataSource ds = TestPostgres.runtimePool(1)) {
            TenantGuc strict = new TenantGuc(ds, RlsSettings.defaults());
            JdbcTransactionManager m = new JdbcTransactionManager(ds);
            m.addListener(new RlsTransactionListener(strict));
            TransactionTemplate t = new TransactionTemplate(m);
            JdbcTemplate j = new JdbcTemplate(ds);
            assertThatThrownBy(() -> t.execute(s -> j.queryForObject("select count(*) from notes", Long.class)))
                    .satisfies(e -> assertThat(RlsErrors.isMissingTenant(e)).as("%s", e).isTrue());
            Long withTenant = TenantContext.callAs(a, () -> t.execute(s ->
                    j.queryForObject("select count(*) from notes where tenant_id = ?", Long.class, a)));
            assertThat(withTenant).isNotNull();
        }
    }

    @Test
    void switchingTenantInsideAnUntrackedTransactionIsRefused() {
        TransactionTemplate untracked = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        assertThatThrownBy(() -> untracked.executeWithoutResult(s -> TenantContext.runAs(a, () -> { })))
                .isInstanceOf(RlsUsageException.class);
    }

    @Test
    void theAdvisoryLockSerialisesTheSameTenantAndNamespace() throws Exception {
        try (HikariDataSource ds = TestPostgres.runtimePool(2)) {
            TenantAdvisoryLock lock = new TenantAdvisoryLock(ds);
            TransactionTemplate t = new TransactionTemplate(new JdbcTransactionManager(ds));
            assertThatThrownBy(() -> lock.lock("quota", a)).isInstanceOf(RlsUsageException.class);

            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> t.executeWithoutResult(s -> {
                lock.lock("quota", a);
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(t.<Boolean>execute(s -> lock.tryLock("quota", a))).as("same namespace, same tenant").isFalse();
            assertThat(t.<Boolean>execute(s -> lock.tryLock("quota", b))).as("other tenant").isTrue();
            assertThat(t.<Boolean>execute(s -> lock.tryLock("export", a))).as("other namespace").isTrue();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(t.<Boolean>execute(s -> lock.tryLock("quota", a))).as("released at commit").isTrue();
        }
    }
}
