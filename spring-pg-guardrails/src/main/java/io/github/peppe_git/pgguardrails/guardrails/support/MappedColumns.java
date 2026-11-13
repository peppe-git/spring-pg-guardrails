// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.descriptor.converter.spi.JpaAttributeConverter;

/**
 * The columns Hibernate maps, as Hibernate itself understands them (naming strategies, embeddables, join columns
 * included). Hibernate-specific by necessity: JPA's metamodel does not expose columns.
 */
public final class MappedColumns {

    private MappedColumns() {
    }

    /**
     * One mapped column.
     *
     * @param entity entity name
     * @param attribute attribute name
     * @param table table expression as Hibernate renders it (may be schema-qualified or quoted)
     * @param column column expression
     * @param nullableInMapping what Hibernate believes ({@code @Column(nullable)}, {@code optional}, ids…)
     * @param javaType the Java type of the attribute (primitive types included)
     * @param member the field or getter, for annotations; may be {@code null}
     * @param jdbcTypeCode the {@code java.sql.Types}/{@code SqlTypes} code Hibernate binds with; {@code null} when the
     *     attribute is not a basic value
     * @param jpaConverter whether a JPA {@code AttributeConverter} sits between the attribute and the column
     * @param singleTableSubclass whether the attribute belongs to a subclass sharing its root's table
     */
    public record Column(String entity, String attribute, String table, String column, boolean nullableInMapping,
            Class<?> javaType, Member member, Integer jdbcTypeCode, boolean jpaConverter,
            boolean singleTableSubclass) {
    }

    /** Every non-formula column of every entity's own attributes; collections are skipped. */
    public static List<Column> of(EntityManagerFactory emf) {
        Objects.requireNonNull(emf, "emf");
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        List<Column> out = new ArrayList<>();
        sf.getMappingMetamodel().forEachEntityDescriptor(persister -> collect(persister, out));
        return out;
    }

    private static void collect(EntityPersister persister, List<Column> out) {
        EntityMappingType root = persister.getRootEntityDescriptor();
        boolean subclassOfSameTable = root != persister
                && Objects.equals(root.getMappedTableDetails().getTableName(),
                        persister.getMappedTableDetails().getTableName());
        persister.forEachAttributeMapping(am -> {
            if (am.isPluralAttributeMapping() || am.getDeclaringType() != persister) {
                return;
            }
            Class<?> javaType = javaType(am);
            Member member = member(am);
            Integer code = null;
            boolean converter = false;
            if (am instanceof BasicValuedMapping basic) {
                code = basic.getJdbcMapping().getJdbcType().getDefaultSqlTypeCode();
                converter = basic.getJdbcMapping().getValueConverter() instanceof JpaAttributeConverter;
            }
            Integer jdbcCode = code;
            boolean jpaConverter = converter;
            am.forEachSelectable((i, selectable) -> {
                if (selectable.isFormula()) {
                    return;
                }
                out.add(new Column(persister.getEntityName(), am.getAttributeName(),
                        selectable.getContainingTableExpression(), selectable.getSelectionExpression(),
                        selectable.isNullable(), javaType, member, jdbcCode, jpaConverter, subclassOfSameTable));
            });
        });
    }

    private static Class<?> javaType(AttributeMapping am) {
        try {
            return am.getPropertyAccess().getGetter().getReturnTypeClass();
        } catch (RuntimeException e) {
            return am.getJavaType().getJavaTypeClass();
        }
    }

    private static Member member(AttributeMapping am) {
        try {
            return am.getPropertyAccess().getGetter().getMember();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
