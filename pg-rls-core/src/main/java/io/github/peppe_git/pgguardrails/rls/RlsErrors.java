// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.sql.SQLException;

/** Helpers to recognise row-level security errors raised by PostgreSQL. */
public final class RlsErrors {

    /** SQLSTATE raised by the library's SQL functions when strict mode is on and no tenant is set. */
    public static final String STRICT_MODE_SQLSTATE = "RLS01";

    /** SQLSTATE PostgreSQL raises when a new row violates a {@code WITH CHECK} (insufficient_privilege). */
    public static final String POLICY_VIOLATION_SQLSTATE = "42501";

    private RlsErrors() {
    }

    /** Whether {@code failure}, or one of its causes, is the strict-mode "no tenant" error. */
    public static boolean isMissingTenant(Throwable failure) {
        return hasSqlState(failure, STRICT_MODE_SQLSTATE);
    }

    /** Whether {@code failure}, or one of its causes, carries the given SQLSTATE. */
    public static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
