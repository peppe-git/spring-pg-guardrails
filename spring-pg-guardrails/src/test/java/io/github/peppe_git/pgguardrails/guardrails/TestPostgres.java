// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** One PostgreSQL container per test JVM; these rules read the catalog, so the superuser is fine. */
final class TestPostgres {

    private static GenericContainer<?> container;

    private TestPostgres() {
    }

    static synchronized DataSource dataSource() {
        if (container == null) {
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(System.getProperty("pg.image",
                    "postgres:17-alpine")))
                    .withEnv("POSTGRES_PASSWORD", "superuser-test-only")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            c.start();
            container = c;
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/postgres");
        ds.setUser("postgres");
        ds.setPassword("superuser-test-only");
        return ds;
    }

    static void execute(String sql) {
        try (Connection con = dataSource().getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
