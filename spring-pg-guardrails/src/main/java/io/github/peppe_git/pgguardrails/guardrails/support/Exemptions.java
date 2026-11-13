// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exemptions declared by name, each with its reason.
 *
 * <p>An exemption is a decision, so it must say why. And it must keep excusing something: a guard fails when one of
 * its exemptions no longer matches a current finding (the class is gone, or it stopped violating the rule).
 * Otherwise the list rots — and the day a new element takes that name it inherits an absolution nobody reviewed.
 */
public final class Exemptions {

    private final Map<String, String> reasons = new LinkedHashMap<>();

    /** Adds an exemption; {@code reason} is mandatory. */
    public Exemptions add(String id, String reason) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("an exemption needs the id of the element it excuses");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("the exemption for '" + id + "' needs a reason: an exemption is a "
                    + "decision, and the reason is its documentation");
        }
        if (reasons.putIfAbsent(id, reason) != null) {
            throw new IllegalArgumentException("'" + id + "' is exempted twice");
        }
        return this;
    }

    public boolean contains(String id) {
        return reasons.containsKey(id);
    }

    public String reason(String id) {
        return reasons.get(id);
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(reasons);
    }
}
