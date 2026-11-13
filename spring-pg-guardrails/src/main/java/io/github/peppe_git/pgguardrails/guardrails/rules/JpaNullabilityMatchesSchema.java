// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import io.github.peppe_git.pgguardrails.guardrails.support.Catalog;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.MappedColumns;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * An entity never promises "not null" on a column the database leaves nullable.
 *
 * <p>{@code hibernate.hbm2ddl.auto=validate} checks that columns exist and have compatible types; it does not
 * compare nullability. So {@code @Column(nullable = false)} over a nullable column goes unnoticed in both
 * directions, and the dangerous one is this: the code reads a value it believes always present and, one day, finds
 * {@code null}. A <b>primitive</b> field over a nullable column is the same promise, broken louder: reading a
 * {@code NULL} into an {@code int} fails the whole query.
 *
 * <p>The opposite direction (the database enforces, the entity is silent) is almost always harmless —
 * {@code created_at} with a default — and is not reported.
 *
 * <p>Columns come from Hibernate's own mapping model, nullability from the catalog of the database your
 * migrations built. Attributes of {@code SINGLE_TABLE} subclasses are skipped: their columns are nullable by
 * construction.
 */
public final class JpaNullabilityMatchesSchema extends Guard<JpaNullabilityMatchesSchema> {

    private final EntityManagerFactory emf;
    private final Catalog catalog;

    private JpaNullabilityMatchesSchema(EntityManagerFactory emf, DataSource dataSource) {
        this.emf = Objects.requireNonNull(emf, "emf");
        this.catalog = new Catalog(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public static JpaNullabilityMatchesSchema in(EntityManagerFactory emf, DataSource dataSource) {
        return new JpaNullabilityMatchesSchema(emf, dataSource);
    }

    @Override
    protected String name() {
        return "JpaNullabilityMatchesSchema";
    }

    @Override
    protected String rationale() {
        return "schema validation does not compare nullability: an entity that promises non-null over a nullable "
                + "column reads null one day, and a primitive field over a nullable column fails the query";
    }

    @Override
    protected String remedy() {
        return "make the column NOT NULL in a new migration, or drop the promise (nullable = true, a wrapper type); "
                + "exempt by 'table.column' with a reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        Map<String, Map<String, Boolean>> tables = new HashMap<>();
        int skipped = 0;
        for (MappedColumns.Column c : MappedColumns.of(emf)) {
            if (c.singleTableSubclass()) {
                skipped++;
                continue;
            }
            Map<String, Boolean> columns = tables.computeIfAbsent(c.table(), this::nullability);
            String column = Catalog.identifier(c.column());
            String id = Catalog.identifier(c.table()) + "." + column;
            Boolean nullable = columns.get(column);
            if (nullable == null) {
                collector.inconclusive(id, "mapped by " + c.entity() + "." + c.attribute()
                        + " but not found in the database");
                continue;
            }
            collector.examined();
            if (!nullable) {
                continue;
            }
            if (c.javaType() != null && c.javaType().isPrimitive()) {
                collector.violation(id, c.entity() + "." + c.attribute() + " is a primitive " + c.javaType()
                        + " over a nullable column: reading a NULL fails");
            } else if (!c.nullableInMapping()) {
                collector.violation(id, c.entity() + "." + c.attribute() + " is mapped non-null, the column "
                        + "accepts NULL");
            }
        }
        if (skipped > 0) {
            collector.note(skipped + " columns of SINGLE_TABLE subclasses skipped");
        }
    }

    /** {@code column -> is nullable} for a table expression; empty when the table does not exist. */
    private Map<String, Boolean> nullability(String table) {
        Long relation = catalog.relation(table);
        Map<String, Boolean> out = new HashMap<>();
        if (relation == null) {
            return out;
        }
        List<Object[]> rows = catalog.query("select attname::text, not attnotnull from pg_attribute "
                + "where attrelid = ? and attnum > 0 and not attisdropped",
                rs -> new Object[] {rs.getString(1), rs.getBoolean(2)}, relation);
        for (Object[] r : rows) {
            out.put((String) r[0], (Boolean) r[1]);
        }
        return out;
    }
}
