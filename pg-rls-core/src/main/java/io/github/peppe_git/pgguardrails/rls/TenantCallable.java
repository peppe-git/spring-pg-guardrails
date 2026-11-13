// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

/**
 * A unit of work that runs on behalf of a tenant and may throw a checked exception of type {@code X}, which is
 * propagated unchanged (so a servlet filter can call {@code chain.doFilter} inside it).
 *
 * @param <T> the result type
 * @param <X> the checked exception the work may throw
 */
@FunctionalInterface
public interface TenantCallable<T, X extends Throwable> {

    T call() throws X;
}
