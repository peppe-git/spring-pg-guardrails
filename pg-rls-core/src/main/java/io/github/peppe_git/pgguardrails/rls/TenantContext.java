// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant the current thread works for.
 *
 * <p>There is deliberately no public {@code set}/{@code clear}. Threads are pooled (servlet containers, executors,
 * schedulers), so a tenant that is set and not restored stays attached to the thread, and the next piece of work
 * that runs there reads and writes as someone else. The only way in is a scope — {@link #callAs} or
 * {@link #runAs} — which restores the previous value in a {@code finally}, whatever happens inside.
 *
 * <p><b>Inside a transaction.</b> The database only sees the tenant through a transaction-local GUC. When a scope
 * opens inside a Spring transaction that this library is tracking (see {@link RlsTransactionListener}), the GUC of
 * that transaction is switched too, and restored to its previous value when the scope closes. When the transaction
 * is not tracked, switching the tenant is refused with an {@link RlsUsageException}: the thread would say one
 * tenant and the database another.
 *
 * <p>The tenant can be any non-null object whose {@code toString()} is not blank (a {@code UUID}, a {@code Long},
 * a {@code String}); that text is what the GUC receives.
 */
public final class TenantContext {

    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The tenant bound to this thread, if any. */
    public static Optional<Object> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The tenant bound to this thread, as the given type.
     *
     * @throws ClassCastException if a tenant of another type is bound
     */
    public static <T> Optional<T> current(Class<T> type) {
        Object value = CURRENT.get();
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("the bound tenant is a " + value.getClass().getName() + ", not a "
                    + type.getName());
        }
        return Optional.of(type.cast(value));
    }

    /**
     * The tenant bound to this thread.
     *
     * @throws RlsUsageException if none is bound
     */
    public static Object require() {
        Object value = CURRENT.get();
        if (value == null) {
            throw new RlsUsageException("no tenant is bound to this thread; run the work inside "
                    + "TenantContext.callAs(tenant, ...) or TenantContext.runAs(tenant, ...)");
        }
        return value;
    }

    /**
     * Runs {@code work} with {@code tenantId} bound to this thread and restores the previous tenant afterwards.
     *
     * @throws RlsUsageException if a Spring transaction not tracked by this library is open and the tenant would
     *     change (see the class documentation)
     */
    public static <T, X extends Throwable> T callAs(Object tenantId, TenantCallable<T, X> work) throws X {
        toGucValue(tenantId);
        return callWith(tenantId, work);
    }

    /** Like {@link #callAs} without a result. */
    public static <X extends Throwable> void runAs(Object tenantId, TenantRunnable<X> work) throws X {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Captures the tenant bound now and returns a {@link Runnable} that runs {@code task} with that tenant bound,
     * on whatever thread executes it. If no tenant is bound now, the task runs with none (and any tenant left on
     * the executing thread is hidden for its duration).
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Object captured = CURRENT.get();
        return () -> callWith(captured, () -> {
            task.run();
            return null;
        });
    }

    /** Like {@link #wrap(Runnable)} for a {@link Callable}. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        Object captured = CURRENT.get();
        return () -> callWith(captured, task::call);
    }

    /** The text a tenant id becomes in the GUC; rejects values that would read as "no tenant". */
    static String toGucValue(Object tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenant id must not be null");
        }
        String text = tenantId.toString();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank: a blank GUC means 'no tenant'");
        }
        return text;
    }

    /** {@code tenantId} may be null here: that runs the work with no tenant bound. */
    static <T, X extends Throwable> T callWith(Object tenantId, TenantCallable<T, X> work) throws X {
        Objects.requireNonNull(work, "work");
        Object previous = CURRENT.get();
        RlsTransactionState tx = RlsTransactionState.current();
        if (tx == null && !Objects.equals(previous, tenantId)
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RlsUsageException("cannot switch the tenant from " + previous + " to " + tenantId
                    + " inside a transaction this library does not track: the transaction-local GUC would keep "
                    + "the old value. Bind the tenant before the transaction starts, use TenantGuc.runAs(...), or "
                    + "register RlsTransactionListener on the transaction manager.");
        }
        String previousGuc = null;
        if (tx != null) {
            previousGuc = tx.swapTenant(tenantId == null ? "" : toGucValue(tenantId));
        }
        set(tenantId);
        T result;
        try {
            result = work.call();
        } catch (Throwable failure) {
            restore(previous, tx, previousGuc, failure);
            throw failure;
        }
        restore(previous, tx, previousGuc, null);
        return result;
    }

    private static void restore(Object previous, RlsTransactionState tx, String previousGuc, Throwable failure) {
        set(previous);
        if (tx == null || !tx.isCurrent()) {
            return;
        }
        try {
            tx.swapTenant(previousGuc == null ? "" : previousGuc);
        } catch (RuntimeException restoreFailure) {
            if (failure == null) {
                throw restoreFailure;
            }
            // The transaction is usually aborted at this point (a failed statement poisons it in PostgreSQL), so
            // the GUC dies with it: keep the original failure in front.
            failure.addSuppressed(restoreFailure);
        }
    }

    private static void set(Object tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }
}
