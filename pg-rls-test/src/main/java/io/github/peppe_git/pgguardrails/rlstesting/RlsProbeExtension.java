// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ClassUtils;

/**
 * JUnit 5 extension that injects an {@link RlsProbe} into test methods and constructors.
 *
 * <p>The probe needs a <b>superuser</b> {@code DataSource}. It comes from a static member annotated
 * {@link RlsDataSource} if the test class has one, otherwise from the Spring test context — which is only right when
 * the application itself connects as a superuser. GUC names come from an {@link RlsSettings} bean if the context
 * has one, else the defaults.
 */
public final class RlsProbeExtension implements ParameterResolver {

    private static final boolean SPRING_TEST = ClassUtils.isPresent(
            "org.springframework.test.context.junit.jupiter.SpringExtension", RlsProbeExtension.class.getClassLoader());

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        return parameter.getParameter().getType() == RlsProbe.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        Class<?> testClass = extension.getRequiredTestClass();
        DataSource annotated = annotated(testClass);
        if (annotated != null) {
            return RlsProbe.on(annotated);
        }
        if (SPRING_TEST) {
            return Spring.probe(extension);
        }
        throw new ParameterResolutionException("no @RlsDataSource static member on " + testClass.getName()
                + " and no Spring test context: tell RlsProbeExtension where the superuser DataSource is");
    }

    private static DataSource annotated(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(RlsDataSource.class) && Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        return (DataSource) f.get(null);
                    }
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(RlsDataSource.class) && Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        return (DataSource) m.invoke(null);
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new ParameterResolutionException("cannot read the @RlsDataSource member: " + e.getMessage(), e);
            }
        }
        return null;
    }

    /** Kept apart so that spring-test is only loaded when present. */
    private static final class Spring {

        static RlsProbe probe(ExtensionContext extension) {
            ApplicationContext context = SpringExtension.getApplicationContext(extension);
            RlsSettings settings = context.getBeanProvider(RlsSettings.class).getIfAvailable(RlsSettings::defaults);
            return RlsProbe.on(context.getBean(DataSource.class), settings);
        }
    }
}
