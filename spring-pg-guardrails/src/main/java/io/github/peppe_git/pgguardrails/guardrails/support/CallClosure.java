// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Transitive closure of calls into methods of the <b>same class</b>, for rules that ask "on this path, do we ever
 * reach X?".
 *
 * <p>Why the same class only: it is the boundary inside which static reasoning does not lie. Crossing into
 * collaborators means resolving interfaces and Spring proxies, and a wrong answer to "does this call the network?"
 * produces a guard that acquits. Beyond the boundary, rules answer with named exemptions, not heuristics.
 *
 * <p>Method references ({@code this::work}) are followed too; without them the chain breaks at the first
 * {@code executor.execute(this::work)}.
 */
public final class CallClosure {

    private CallClosure() {
    }

    /** {@code root} plus every method of {@code owner} reachable from it. */
    public static Set<JavaMethod> withinSameClass(JavaMethod root, JavaClass owner) {
        Set<JavaMethod> visited = new HashSet<>();
        Deque<JavaMethod> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root);
        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            accessesFrom(current)
                    .filter(a -> a.getTargetOwner().equals(owner))
                    .forEach(a -> resolveMethod(a).filter(visited::add).ifPresent(queue::add));
        }
        return visited;
    }

    /** All outgoing accesses of the given methods: calls and method references. */
    public static Stream<JavaAccess<?>> accessesFrom(Collection<JavaMethod> methods) {
        return methods.stream().flatMap(CallClosure::accessesFrom);
    }

    private static Stream<JavaAccess<?>> accessesFrom(JavaMethod method) {
        return Stream.concat(
                method.getMethodCallsFromSelf().stream().map(a -> (JavaAccess<?>) a),
                method.getMethodReferencesFromSelf().stream().map(a -> (JavaAccess<?>) a));
    }

    private static Optional<JavaMethod> resolveMethod(JavaAccess<?> access) {
        return access.getTarget().resolveMember()
                .filter(JavaMethod.class::isInstance)
                .map(JavaMethod.class::cast);
    }
}
