// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Minimal read-only access to the PostgreSQL catalog over plain JDBC.
 *
 * <p>Rules that compare code with the schema ask the catalog, not the migration files: the catalog is what the
 * database actually enforces, including what was created by {@code DO} blocks, functions and dynamic SQL that no
 * migration parser sees.
 */
public final class Catalog {

    private final DataSource dataSource;

    public Catalog(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** A row of a catalog query. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<T> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("catalog query failed: " + e.getMessage(), e);
        }
    }

    /** The oid of a relation resolved with the connection's search_path, or {@code null}. */
    public Long relation(String name) {
        List<Long> oid = query("select to_regclass(?)::oid", rs -> {
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }, name);
        return oid.isEmpty() ? null : oid.get(0);
    }

    /** Strips double quotes; folds unquoted identifiers to lower case, as PostgreSQL does. */
    public static String identifier(String raw) {
        String t = raw.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t.toLowerCase(java.util.Locale.ROOT);
    }
}
