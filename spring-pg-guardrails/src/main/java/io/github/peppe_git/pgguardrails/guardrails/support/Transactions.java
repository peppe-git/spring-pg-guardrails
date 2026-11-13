// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises, from bytecode alone, the methods whose whole body runs inside a transaction because of an annotation
 * (Spring's or Jakarta's {@code @Transactional}, directly or as a meta-annotation, on the method or its class).
 *
 * <p>Annotations are matched by name, so the rules need neither spring-tx nor jakarta.transaction on the classpath.
 */
public final class Transactions {

    public static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    public static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final List<String> ANNOTATIONS = List.of(SPRING_TRANSACTIONAL, JAKARTA_TRANSACTIONAL);

    /** Propagations under which the annotated body does not run inside a transaction. */
    private static final Set<String> NOT_IN_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER", "SUPPORTS");

    private Transactions() {
    }

    /**
     * Whether the body of {@code method} runs in a transaction because of an annotation: a method-level annotation
     * wins over a class-level one, and propagations {@code SUPPORTS}, {@code NOT_SUPPORTED} and {@code NEVER} do not
     * count. Class-level annotations apply to non-private, non-static methods.
     */
    public static boolean runsInTransaction(JavaMethod method) {
        Optional<JavaAnnotation<?>> own = transactional(method.getAnnotations());
        if (own.isPresent()) {
            return opens(own.get());
        }
        if (method.getModifiers().contains(JavaModifier.PRIVATE) || method.getModifiers().contains(JavaModifier.STATIC)
                || method.getModifiers().contains(JavaModifier.SYNTHETIC)) {
            return false;
        }
        Optional<JavaAnnotation<?>> onClass = onClass(method.getOwner());
        return onClass.isPresent() && opens(onClass.get());
    }

    private static Optional<JavaAnnotation<?>> onClass(JavaClass owner) {
        return transactional(owner.getAnnotations());
    }

    private static Optional<JavaAnnotation<?>> transactional(Set<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> a : annotations) {
            if (ANNOTATIONS.contains(a.getRawType().getName())) {
                return Optional.of(a);
            }
        }
        // one level of meta-annotation: a composed annotation that carries @Transactional
        for (JavaAnnotation<?> a : annotations) {
            for (String name : ANNOTATIONS) {
                Optional<? extends JavaAnnotation<?>> meta = a.getRawType().tryGetAnnotationOfType(name);
                if (meta.isPresent()) {
                    return Optional.of(meta.get());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean opens(JavaAnnotation<?> annotation) {
        Optional<Object> propagation = annotation.get("propagation");
        if (propagation.isEmpty()) {
            propagation = annotation.get("value"); // jakarta: TxType value()
        }
        if (propagation.isPresent() && propagation.get() instanceof JavaEnumConstant constant) {
            return !NOT_IN_TRANSACTION.contains(constant.name());
        }
        return true;
    }
}
