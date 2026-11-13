// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container per test JVM, with the library's SQL installed and a runtime role that is subject to
 * row-level security. The superuser connection exists only to set things up: it bypasses every policy.
 */
final class TestPostgres {

    static final String RUNTIME_ROLE = "app_runtime";
    static final String RUNTIME_PASSWORD = "runtime-test-only";
    static final String SUPERUSER_PASSWORD = "superuser-test-only";

    private static GenericContainer<?> container;

    private TestPostgres() {
    }

    static synchronized void start() {
        if (container != null) {
            return;
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(System.getProperty("pg.image",
                "postgres:17-alpine")))
                .withEnv("POSTGRES_PASSWORD", SUPERUSER_PASSWORD)
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
        c.start();
        container = c;
        execute(RlsSql.install(RlsSettings.defaults(), RlsSql.DEFAULT_SCHEMA));
        execute("""
                create table notes (
                    id bigserial primary key,
                    tenant_id uuid not null,
                    body text not null
                );
                select rls.enable_tenant_rls('notes', 'tenant_id', true);
                create table counters (
                    tenant_id uuid primary key,
                    value int not null default 0
                );
                select rls.enable_tenant_rls('counters');
                """);
        execute(RlsSql.runtimeRole(RUNTIME_ROLE, RlsSql.DEFAULT_SCHEMA));
        execute("alter role " + RUNTIME_ROLE + " password '" + RUNTIME_PASSWORD + "'");
    }

    static String jdbcUrl() {
        start();
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/postgres";
    }

    static DataSource superuser() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl());
        ds.setUser("postgres");
        ds.setPassword(SUPERUSER_PASSWORD);
        return ds;
    }

    static DataSource runtime() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl());
        ds.setUser(RUNTIME_ROLE);
        ds.setPassword(RUNTIME_PASSWORD);
        return ds;
    }

    /** A pool of the runtime role; with size 1, consecutive transactions share the physical connection. */
    static HikariDataSource runtimePool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(RUNTIME_ROLE);
        config.setPassword(RUNTIME_PASSWORD);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }

    static void execute(String sql) {
        try (Connection con = superuser().getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("setup SQL failed: " + e.getMessage(), e);
        }
    }
}
