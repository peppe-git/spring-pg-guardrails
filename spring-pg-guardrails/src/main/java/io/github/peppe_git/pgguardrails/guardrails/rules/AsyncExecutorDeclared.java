// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import org.springframework.aop.interceptor.AsyncExecutionAspectSupport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Every {@code @Async} method of the application context runs on an executor somebody chose.
 *
 * <p>Two silent failures, both decided by framework conditions rather than by any line of the application:
 * <ul>
 *   <li>{@code @Async} without {@code @EnableAsync}: the annotation is inert, the method runs on the caller's
 *       thread (inside the caller's transaction, holding the caller's request);
 *   <li>{@code @EnableAsync} with no executor Spring can pick: it falls back to {@code SimpleAsyncTaskExecutor},
 *       one new thread per call, no bound. It happens as soon as the application declares its own {@code Executor}
 *       bean: Spring Boot's auto-configured {@code applicationTaskExecutor} backs off, and with two or more
 *       {@code TaskExecutor} beans none of which is called {@code taskExecutor}, Spring finds no default.
 * </ul>
 *
 * <p>The rule asks Spring itself, through {@link AsyncExecutionAspectSupport#getDefaultExecutor}, which executor it
 * would use, so it follows the framework's own resolution (and its changes between versions). An
 * {@link AsyncConfigurer} that overrides {@code getAsyncExecutor()} counts as a choice. Qualified
 * {@code @Async("name")} must name an existing {@code Executor} bean.
 */
public final class AsyncExecutorDeclared extends Guard<AsyncExecutorDeclared> {

    private final ConfigurableApplicationContext context;

    private AsyncExecutorDeclared(ConfigurableApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public static AsyncExecutorDeclared in(ConfigurableApplicationContext context) {
        return new AsyncExecutorDeclared(context);
    }

    @Override
    protected String name() {
        return "AsyncExecutorDeclared";
    }

    @Override
    protected String rationale() {
        return "@Async either does nothing (no @EnableAsync) or falls back to SimpleAsyncTaskExecutor, one unbounded "
                + "new thread per call, as soon as the application declares its own Executor beans";
    }

    @Override
    protected String remedy() {
        return "add @EnableAsync, and declare the executor: a bean named 'taskExecutor' (or 'applicationTaskExecutor' "
                + "with Spring Boot), an AsyncConfigurer overriding getAsyncExecutor(), or @Async(\"beanName\")";
    }

    @Override
    protected void evaluate(Collector collector) {
        ConfigurableListableBeanFactory beans = context.getBeanFactory();
        Map<String, String> asyncMethods = new TreeMap<>();
        TreeSet<String> qualifiers = new TreeSet<>();
        boolean needsDefault = false;
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name, false);
            if (type == null) {
                continue;
            }
            collector.examined();
            Class<?> user = ClassUtils.getUserClass(type);
            Async onClass = AnnotatedElementUtils.findMergedAnnotation(user, Async.class);
            for (Method m : ReflectionUtils.getUniqueDeclaredMethods(user, ReflectionUtils.USER_DECLARED_METHODS)) {
                Async async = AnnotatedElementUtils.findMergedAnnotation(m, Async.class);
                if (async == null) {
                    async = onClass;
                }
                if (async == null || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                asyncMethods.put(user.getName() + "#" + m.getName(), async.value());
                if (async.value().isEmpty()) {
                    needsDefault = true;
                } else {
                    qualifiers.add(async.value());
                }
            }
        }
        if (asyncMethods.isEmpty()) {
            collector.note("no @Async method in the context");
            return;
        }
        if (!beans.containsBean(TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)) {
            collector.violation("async-not-enabled", "@Async on " + asyncMethods.keySet()
                    + " but @EnableAsync is missing: these methods run synchronously on the caller's thread");
            return;
        }
        Resolver resolver = new Resolver();
        for (String qualifier : qualifiers) {
            try {
                resolver.qualified(beans, qualifier);
            } catch (BeansException missing) {
                collector.violation("qualifier:" + qualifier, "@Async(\"" + qualifier + "\") names no Executor "
                        + "bean: " + missing.getMessage());
            }
        }
        if (needsDefault && !asyncConfigurerChoosesExecutor(beans) && resolver.defaultExecutor(beans) == null) {
            collector.violation("default-executor", "unqualified @Async on " + asyncMethods.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList()
                    + " would run on SimpleAsyncTaskExecutor (one new thread per call, no bound): Spring finds no "
                    + "unique TaskExecutor bean and no Executor bean named 'taskExecutor'");
        }
    }

    private static boolean asyncConfigurerChoosesExecutor(ConfigurableListableBeanFactory beans) {
        for (String name : beans.getBeanNamesForType(AsyncConfigurer.class, true, false)) {
            Class<?> type = ClassUtils.getUserClass(Objects.requireNonNull(beans.getType(name)));
            Method method = ReflectionUtils.findMethod(type, "getAsyncExecutor");
            if (method != null && method.getDeclaringClass() != AsyncConfigurer.class) {
                return true;
            }
        }
        return false;
    }

    /** Exposes Spring's own resolution of the default and of qualified executors. */
    private static final class Resolver extends AsyncExecutionAspectSupport {

        Resolver() {
            super(null);
        }

        Executor defaultExecutor(BeanFactory beanFactory) {
            return getDefaultExecutor(beanFactory);
        }

        Executor qualified(BeanFactory beanFactory, String qualifier) {
            return findQualifiedExecutor(beanFactory, qualifier);
        }

        @Override
        protected String getExecutorQualifier(Method method) {
            return null;
        }
    }
}
