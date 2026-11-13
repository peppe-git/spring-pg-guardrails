// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import java.util.List;
import java.util.Objects;

/**
 * A Spring component with more than one constructor designates the one Spring must use.
 *
 * <p>With several constructors and none annotated {@code @Autowired} (or {@code @Inject}), Spring does not pick one:
 * it looks for the no-argument constructor. If there is none, the context fails to start — typically in production,
 * because tests build the class with {@code new} (where the compiler picks the overload) or never activate the
 * conditional bean. If there is one, Spring uses it silently and the dependencies are never injected. The usual cause
 * is a second constructor added for one more collaborator.
 *
 * <p>Components are classes annotated, directly or through a meta-annotation, with {@code @Component} (so
 * {@code @Service}, {@code @Repository}, {@code @Controller}, {@code @Configuration}…). Synthetic constructors are
 * ignored.
 */
public final class UnambiguousComponentConstructors extends Guard<UnambiguousComponentConstructors> {

    private static final String COMPONENT = "org.springframework.stereotype.Component";

    private static final List<String> DESIGNATORS = List.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.inject.Inject",
            "javax.inject.Inject");

    private final JavaClasses classes;

    private UnambiguousComponentConstructors(JavaClasses classes) {
        this.classes = Objects.requireNonNull(classes, "classes");
    }

    public static UnambiguousComponentConstructors in(JavaClasses classes) {
        return new UnambiguousComponentConstructors(classes);
    }

    @Override
    protected String name() {
        return "UnambiguousComponentConstructors";
    }

    @Override
    protected String rationale() {
        return "with several constructors and none designated, Spring falls back to the no-argument constructor: "
                + "the context fails to start, or starts with dependencies never injected";
    }

    @Override
    protected String remedy() {
        return "annotate the constructor Spring must use with @Autowired, or keep a single constructor";
    }

    @Override
    protected void evaluate(Collector collector) {
        for (JavaClass clazz : classes) {
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.getModifiers().contains(JavaModifier.ABSTRACT)
                    || !(clazz.isAnnotatedWith(COMPONENT) || clazz.isMetaAnnotatedWith(COMPONENT))) {
                continue;
            }
            collector.examined();
            List<JavaConstructor> constructors = clazz.getConstructors().stream()
                    .filter(c -> !c.getModifiers().contains(JavaModifier.SYNTHETIC))
                    .toList();
            if (constructors.size() < 2) {
                continue;
            }
            boolean designated = constructors.stream()
                    .anyMatch(c -> DESIGNATORS.stream().anyMatch(c::isAnnotatedWith));
            if (!designated) {
                collector.violation(clazz.getName(), constructors.size() + " constructors and none annotated "
                        + "@Autowired: " + constructors.stream().map(JavaConstructor::getFullName).toList());
            }
        }
    }
}
