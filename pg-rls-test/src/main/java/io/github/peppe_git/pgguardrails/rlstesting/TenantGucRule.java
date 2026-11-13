// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.peppe_git.pgguardrails.guardrails.support.CallClosure;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.Transactions;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every method that opens a transaction and reaches a repository of a tenant table also sets the tenant GUC on that
 * path.
 *
 * <p>For applications that set the GUC explicitly ({@code pg-rls.apply-on-transaction-begin=false}, or code written
 * before adopting the library). With the setting applied automatically at transaction begin, the question becomes
 * "is the tenant bound when the transaction starts?", which no static rule can answer: rely on strict mode there.
 *
 * <p>How it decides, from bytecode only:
 * <ul>
 *   <li>tenant repositories are derived: Spring Data repository interfaces whose entity's table (from
 *       {@code @Table(name)}, else the snake_case entity name) is one of the RLS tables you pass — typically
 *       {@link RlsCatalog#rlsTableNames(String)} of the test database;</li>
 *   <li>a method opens a transaction if it is {@code @Transactional} (method or class, Spring or Jakarta) or calls
 *       {@code TransactionTemplate}/{@code TransactionOperations.execute*};</li>
 *   <li>the path is the method plus the methods of the same class it reaches ({@link CallClosure});</li>
 *   <li>the GUC is set if the path calls an applier: by default {@code TenantGuc.apply/applyFromContext/runAs} and
 *       {@code TenantContext.callAs/runAs} of pg-rls-core; add your own with {@link #applier}.</li>
 * </ul>
 * Not seen: access through {@code JdbcTemplate} or native SQL (not attributable to a table statically) and paths
 * through other beans. Exempt by {@code com.acme.Service#method}.
 */
public final class TenantGucRule extends Guard<TenantGucRule> {

    private static final String REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String TABLE = "jakarta.persistence.Table";

    private final JavaClasses classes;
    private final Set<String> rlsTables;
    private final Map<String, Set<String>> appliers = new LinkedHashMap<>();

    private TenantGucRule(JavaClasses classes, Collection<String> rlsTables) {
        this.classes = Objects.requireNonNull(classes, "classes");
        this.rlsTables = new HashSet<>();
        rlsTables.forEach(t -> this.rlsTables.add(t.toLowerCase(Locale.ROOT)));
        if (this.rlsTables.isEmpty()) {
            throw new IllegalArgumentException("no RLS table given: the rule would examine nothing");
        }
        applier("io.github.peppe_git.pgguardrails.rls.TenantGuc", "apply", "applyFromContext", "runAs");
        applier("io.github.peppe_git.pgguardrails.rls.TenantContext", "callAs", "runAs");
    }

    /** {@code rlsTables}: unqualified names of the tables with row-level security. */
    public static TenantGucRule in(JavaClasses classes, Collection<String> rlsTables) {
        return new TenantGucRule(classes, rlsTables);
    }

    /**
     * Calls to these methods of {@code ownerType} set the GUC (your own wrapper, or a bean that delegates after an
     * authorisation check).
     */
    public TenantGucRule applier(String ownerType, String... methods) {
        appliers.computeIfAbsent(ownerType, k -> new LinkedHashSet<>()).addAll(List.of(methods));
        return this;
    }

    @Override
    protected String name() {
        return "TenantGucRule";
    }

    @Override
    protected String rationale() {
        return "a transaction that reads a tenant table without setting the tenant GUC sees zero rows (or, with "
                + "the admin escape mis-used, too many) — and superuser integration tests hide it";
    }

    @Override
    protected String remedy() {
        return "set the tenant as the first thing in the transaction (TenantGuc.apply / runAs, or bind it with "
                + "TenantContext before the transaction starts and enable apply-on-transaction-begin)";
    }

    @Override
    protected void evaluate(Collector collector) {
        Set<String> repositories = tenantRepositories();
        if (repositories.isEmpty()) {
            collector.note("no Spring Data repository maps one of the RLS tables " + new TreeSet<>(rlsTables));
            return;
        }
        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                Set<JavaMethod> path = CallClosure.withinSameClass(method, clazz);
                if (!opensTransaction(method, path)) {
                    continue;
                }
                boolean touches = CallClosure.accessesFrom(path)
                        .anyMatch(a -> repositories.contains(a.getTargetOwner().getName()));
                if (!touches) {
                    continue;
                }
                collector.examined();
                if (CallClosure.accessesFrom(path).noneMatch(this::applies)) {
                    collector.violation(clazz.getName() + "#" + method.getName(), "opens a transaction, reaches "
                            + "a tenant repository and never sets the tenant GUC");
                }
            }
        }
    }

    private boolean applies(JavaAccess<?> access) {
        Set<String> methods = appliers.get(access.getTargetOwner().getName());
        return methods != null && methods.contains(access.getName());
    }

    private static boolean opensTransaction(JavaMethod method, Set<JavaMethod> path) {
        if (Transactions.runsInTransaction(method)) {
            return true;
        }
        return CallClosure.accessesFrom(path).anyMatch(a -> {
            String owner = a.getTargetOwner().getName();
            return (owner.equals("org.springframework.transaction.support.TransactionTemplate")
                    || owner.equals("org.springframework.transaction.support.TransactionOperations"))
                    && a.getName().startsWith("execute");
        });
    }

    private Set<String> tenantRepositories() {
        Set<String> tenantEntities = new HashSet<>();
        for (JavaClass clazz : classes) {
            if (clazz.isAnnotatedWith(ENTITY) && rlsTables.contains(tableOf(clazz))) {
                tenantEntities.add(clazz.getName());
            }
        }
        Set<String> repositories = new HashSet<>();
        for (JavaClass clazz : classes) {
            if (clazz.isInterface() && clazz.isAssignableTo(REPOSITORY)) {
                entityOf(clazz).filter(tenantEntities::contains).ifPresent(e -> repositories.add(clazz.getName()));
            }
        }
        return repositories;
    }

    private static String tableOf(JavaClass entity) {
        Optional<? extends JavaAnnotation<?>> table = entity.tryGetAnnotationOfType(TABLE);
        if (table.isPresent()) {
            Object name = table.get().get("name").orElse("");
            if (name instanceof String s && !s.isEmpty()) {
                return s.replace("\"", "").toLowerCase(Locale.ROOT);
            }
        }
        return entity.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** The entity type argument of a Spring Data repository, following intermediate interfaces. */
    private static Optional<String> entityOf(JavaClass repository) {
        for (JavaType iface : repository.getInterfaces()) {
            if (iface instanceof JavaParameterizedType parameterized
                    && parameterized.toErasure().isAssignableTo(REPOSITORY)
                    && !parameterized.getActualTypeArguments().isEmpty()) {
                JavaType first = parameterized.getActualTypeArguments().get(0);
                if (first instanceof JavaClass entity) {
                    return Optional.of(entity.getName());
                }
            }
            JavaClass raw = iface.toErasure();
            if (raw.isAssignableTo(REPOSITORY) && !raw.getName().startsWith("org.springframework.")) {
                Optional<String> nested = entityOf(raw);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }
}
