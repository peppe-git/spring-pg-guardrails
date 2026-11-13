// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import io.github.peppe_git.pgguardrails.guardrails.support.Catalog;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.MappedColumns;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.AnnotatedElement;
import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * A Java enum stored by name, and the {@code CHECK} constraint (or PostgreSQL {@code enum} type) that restricts the
 * same column, allow exactly the same values.
 *
 * <p>The set of allowed values lives twice — in the enum and in the schema — and drifts in two ways. A constant added
 * in Java without its migration passes every test that mocks the database and fails in production with a constraint
 * violation the first time it is written. A value added to the schema (or still present after the constant was
 * removed) cannot be read back: Hibernate throws on the unknown name. A mock cannot catch either; the catalog of a
 * real database can.
 *
 * <p>Scope, derived: every enum attribute that Hibernate binds as a string (the {@code @Enumerated(STRING)} family)
 * or as a named PostgreSQL enum. The allowed set is read from the column's {@code enum} type, or from the
 * single-column {@code CHECK} constraints of the form {@code col IN (...)} / {@code col = ANY (ARRAY[...])}.
 * Skipped, and listed as notes: ordinal mappings, attributes behind a JPA {@code AttributeConverter}, enums with an
 * {@code @EnumeratedValue}. Columns with no such constraint are not violations unless {@link #requireConstraint()}.
 */
public final class EnumValuesMatchCheckConstraints extends Guard<EnumValuesMatchCheckConstraints> {

    private static final Set<Integer> STRING_CODES = Set.of(Types.VARCHAR, Types.CHAR, Types.NVARCHAR, Types.NCHAR,
            Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.OTHER, 6001 /* SqlTypes.NAMED_ENUM */,
            6000 /* SqlTypes.ENUM */);

    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    /**
     * The shapes PostgreSQL gives a set-like CHECK once it has normalised it: {@code col IN ('a','b')} becomes
     * {@code ((col)::text = ANY ((ARRAY['a'::character varying, ...])::text[]))}.
     */
    private static final Pattern SET_LIKE = Pattern.compile("(?i)=\\s*any\\s*\\(+\\s*array\\[|\\bin\\s*\\(");

    private final EntityManagerFactory emf;
    private final Catalog catalog;
    private boolean requireConstraint;

    private EnumValuesMatchCheckConstraints(EntityManagerFactory emf, DataSource dataSource) {
        this.emf = Objects.requireNonNull(emf, "emf");
        this.catalog = new Catalog(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public static EnumValuesMatchCheckConstraints in(EntityManagerFactory emf, DataSource dataSource) {
        return new EnumValuesMatchCheckConstraints(emf, dataSource);
    }

    /** Also reports enum columns that the schema does not restrict at all. */
    public EnumValuesMatchCheckConstraints requireConstraint() {
        this.requireConstraint = true;
        return this;
    }

    @Override
    protected String name() {
        return "EnumValuesMatchCheckConstraints";
    }

    @Override
    protected String rationale() {
        return "an enum and the CHECK constraint on its column drift apart: a new constant fails on its first write in "
                + "production, a value the enum lacks cannot be read back";
    }

    @Override
    protected String remedy() {
        return "write a migration that makes the constraint list exactly the enum's names (or add/remove the "
                + "constant); exempt by 'table.column' with a reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        Set<String> seen = new TreeSet<>();
        for (MappedColumns.Column c : MappedColumns.of(emf)) {
            if (c.javaType() == null || !c.javaType().isEnum()) {
                continue;
            }
            String table = Catalog.identifier(c.table());
            String column = Catalog.identifier(c.column());
            String id = table + "." + column;
            if (!seen.add(id + "@" + c.entity())) {
                continue;
            }
            if (c.jpaConverter()) {
                collector.note(id + ": skipped, mapped through an AttributeConverter");
                continue;
            }
            if (c.jdbcTypeCode() == null || !STRING_CODES.contains(c.jdbcTypeCode())) {
                collector.note(id + ": skipped, enum stored by ordinal");
                continue;
            }
            if (hasEnumeratedValue(c.javaType())) {
                collector.note(id + ": skipped, enum uses @EnumeratedValue");
                continue;
            }
            Long relation = catalog.relation(c.table());
            if (relation == null) {
                collector.inconclusive(id, "table not found in the database");
                continue;
            }
            Set<String> javaValues = new LinkedHashSet<>();
            for (Object constant : c.javaType().getEnumConstants()) {
                javaValues.add(((Enum<?>) constant).name());
            }
            Set<String> allowed = allowedValues(relation, column);
            collector.examined();
            if (allowed == null) {
                if (requireConstraint) {
                    collector.violation(id, c.javaType().getSimpleName() + " is stored here, but the column "
                            + "accepts any string");
                }
                continue;
            }
            Set<String> missingInDb = new TreeSet<>(javaValues);
            missingInDb.removeAll(allowed);
            Set<String> unknownToJava = new TreeSet<>(allowed);
            unknownToJava.removeAll(javaValues);
            if (!missingInDb.isEmpty() || !unknownToJava.isEmpty()) {
                StringBuilder msg = new StringBuilder(c.javaType().getSimpleName()).append(" vs schema:");
                if (!missingInDb.isEmpty()) {
                    msg.append(" the database rejects ").append(missingInDb).append(" (fails on first write);");
                }
                if (!unknownToJava.isEmpty()) {
                    msg.append(" the enum lacks ").append(unknownToJava).append(" (fails on read);");
                }
                collector.violation(id, msg.toString());
            }
        }
    }

    /** Values allowed by the column's enum type or its set-like CHECK constraints; {@code null} when unrestricted. */
    private Set<String> allowedValues(long relation, String column) {
        List<String> enumLabels = catalog.query("""
                select e.enumlabel::text
                  from pg_attribute a
                  join pg_type t on t.oid = a.atttypid
                  join pg_enum e on e.enumtypid = t.oid
                 where a.attrelid = ? and a.attname = ? and t.typtype = 'e'
                 order by e.enumsortorder
                """, rs -> rs.getString(1), relation, column);
        if (!enumLabels.isEmpty()) {
            return new LinkedHashSet<>(enumLabels);
        }
        List<String> definitions = catalog.query("""
                select pg_get_constraintdef(c.oid)
                  from pg_constraint c
                  join pg_attribute a on a.attrelid = c.conrelid and a.attname = ?
                 where c.conrelid = ? and c.contype = 'c' and c.conkey = array[a.attnum]::int2[]
                """, rs -> rs.getString(1), column, relation);
        Set<String> allowed = null;
        for (String def : definitions) {
            if (!SET_LIKE.matcher(def).find()) {
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            Matcher m = LITERAL.matcher(def);
            while (m.find()) {
                values.add(m.group(1).replace("''", "'"));
            }
            if (allowed == null) {
                allowed = values;
            } else {
                allowed.retainAll(values);
            }
        }
        return allowed;
    }

    private static boolean hasEnumeratedValue(Class<?> enumType) {
        return Arrays.stream(enumType.getDeclaredFields()).map(f -> (AnnotatedElement) f)
                .anyMatch(f -> Arrays.stream(f.getAnnotations())
                        .anyMatch(a -> a.annotationType().getName().equals("jakarta.persistence.EnumeratedValue")));
    }
}
