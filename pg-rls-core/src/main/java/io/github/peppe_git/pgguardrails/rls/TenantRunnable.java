// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

/**
 * A unit of work without a result that runs on behalf of a tenant and may throw a checked exception of type
 * {@code X}.
 *
 * @param <X> the checked exception the work may throw
 */
@FunctionalInterface
public interface TenantRunnable<X extends Throwable> {

    void run() throws X;
}
