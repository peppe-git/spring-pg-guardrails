// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import io.github.peppe_git.pgguardrails.rls.RlsSql;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL per test JVM with three schemas:
 * <ul>
 *   <li>{@code public}: a correct application (plus {@code users}, a tenant table outside RLS by design);</li>
 *   <li>{@code broken}: one table per known way of getting RLS wrong;</li>
 *   <li>{@code needs_fixture}: a table whose rows the generator cannot invent.</li>
 * </ul>
 */
final class TestDatabase {

    private static GenericContainer<?> container;

    private TestDatabase() {
    }

    static synchronized DataSource superuser() {
        if (container == null) {
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(System.getProperty("pg.image",
                    "postgres:17-alpine")))
                    .withEnv("POSTGRES_PASSWORD", "superuser-test-only")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            c.start();
            container = c;
            install();
        }
        return dataSource("postgres", "superuser-test-only");
    }

    static DataSource dataSource(String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/postgres");
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    static void execute(String sql) {
        try (Connection con = superuser().getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void install() {
        execute(RlsSql.install(RlsSettings.defaults(), RlsSql.DEFAULT_SCHEMA));
        execute("""
                create type task_kind as enum ('chore', 'bug');

                create table good_projects (
                    id bigserial primary key,
                    tenant_id uuid not null,
                    name text not null,
                    status text not null check (status in ('open', 'closed'))
                );
                select rls.enable_tenant_rls('good_projects', 'tenant_id', true);

                create table good_tasks (
                    id bigserial primary key,
                    tenant_id uuid not null,
                    project_id bigint not null references good_projects (id),
                    title varchar(40) not null,
                    done boolean not null default false,
                    kind task_kind not null,
                    due timestamptz not null
                );
                select rls.enable_tenant_rls('good_tasks');

                create table good_events (
                    tenant_id uuid not null,
                    at date not null,
                    payload jsonb not null
                ) partition by range (at);
                create table good_events_default partition of good_events default;
                select rls.enable_tenant_rls('good_events');

                -- a tenant table deliberately outside RLS: read before the tenant is known (login)
                create table users (id bigserial primary key, tenant_id uuid not null, email text not null unique);
                """);
        execute("""
                create schema broken;

                create table broken.no_rls (tenant_id uuid not null, v text);

                create table broken.not_forced (tenant_id uuid not null, v text);
                alter table broken.not_forced enable row level security;
                create policy p on broken.not_forced
                  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

                create table broken.no_policy (tenant_id uuid not null, v text);
                alter table broken.no_policy enable row level security;
                alter table broken.no_policy force row level security;

                create table broken.legacy_admin (tenant_id uuid not null, v text);
                alter table broken.legacy_admin enable row level security;
                alter table broken.legacy_admin force row level security;
                create policy p on broken.legacy_admin
                  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                         or current_setting('app.rls_admin_read', true) = 'on')
                  with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

                create table broken.open_check (tenant_id uuid not null, v text);
                alter table broken.open_check enable row level security;
                alter table broken.open_check force row level security;
                create policy p on broken.open_check
                  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
                  with check (true);

                create table broken.leaky_read (tenant_id uuid not null, v text);
                alter table broken.leaky_read enable row level security;
                alter table broken.leaky_read force row level security;
                create policy p_read on broken.leaky_read for select using (true);
                create policy p_write on broken.leaky_read for insert
                  with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

                create schema needs_fixture;
                create table needs_fixture.codes (
                    tenant_id uuid not null,
                    code text not null check (length(code) = 5)
                );
                select rls.enable_tenant_rls('needs_fixture.codes');
                """);
    }
}
