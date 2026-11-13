// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The database the way production has it: bootstrapped by the infrastructure script (runtime role), migrated by
 * Flyway as the owner, used by the application as the runtime role.
 */
final class DemoDatabase {

    static final String RUNTIME_PASSWORD = "runtime-test-only";
    private static final String SUPERUSER_PASSWORD = "superuser-test-only";
    private static GenericContainer<?> container;

    private DemoDatabase() {
    }

    static synchronized String url() {
        if (container == null) {
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(System.getProperty("pg.image",
                    "postgres:17-alpine")))
                    .withEnv("POSTGRES_PASSWORD", SUPERUSER_PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            c.start();
            container = c;
            execute(resource("/db/bootstrap/runtime-role.sql"));
            execute("alter role app_runtime password '" + RUNTIME_PASSWORD + "'");
        }
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/postgres";
    }

    /** Spring properties: the application as {@code app_runtime}, Flyway as the owner. */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DemoDatabase::url);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", DemoDatabase::url);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> SUPERUSER_PASSWORD);
    }

    /** The owner/superuser connection, for the guards that read the catalog or need to SET ROLE. */
    static DataSource superuser() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url());
        ds.setUser("postgres");
        ds.setPassword(SUPERUSER_PASSWORD);
        return ds;
    }

    static void execute(String sql) {
        try (Connection con = superuser().getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    static String resource(String path) {
        try (InputStream in = DemoDatabase.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
