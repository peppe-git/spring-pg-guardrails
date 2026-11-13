// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.util.Objects;

/**
 * One thing a guard objects to.
 *
 * @param id stable key of the offending element ({@code com.acme.Foo#bar}, {@code table.column}, a file name):
 *     the key an exemption names
 * @param message what is wrong, in one line
 */
public record Finding(String id, String message) {

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return id + ": " + message;
    }
}
