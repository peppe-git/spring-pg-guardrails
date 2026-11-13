// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

/**
 * Thrown when the row-level security API is used in a way that would make the database see a different tenant
 * than the application believes: a GUC set outside a transaction, on an auto-commit connection, a tenant switch
 * that cannot reach the open transaction, or the admin read escape inside a read-write transaction.
 *
 * <p>Every one of these mistakes is silent without this exception: row-level security is fail-closed, so the
 * symptom would be "zero rows", not an error.
 */
public class RlsUsageException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public RlsUsageException(String message) {
        super(message);
    }
}
