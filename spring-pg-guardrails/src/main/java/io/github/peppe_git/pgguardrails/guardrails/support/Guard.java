// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class of every guard: a floor on how much it must examine, and exemptions by name that cannot rot.
 *
 * <p>Subclasses implement {@link #evaluate(Collector)}, reporting every element they look at and every finding.
 * {@link #check()} turns that into a {@link GuardReport}.
 *
 * @param <SELF> the concrete guard, for fluent configuration
 */
public abstract class Guard<SELF extends Guard<SELF>> {

    private final Exemptions exemptions = new Exemptions();
    private int minimumExamined = 1;

    /** Human-readable name of the rule. */
    protected abstract String name();

    /** Why the rule exists, printed when it fails. */
    protected abstract String rationale();

    /** How to fix a violation, printed when it fails. */
    protected abstract String remedy();

    /** Looks at the input and reports through {@code collector}. */
    protected abstract void evaluate(Collector collector);

    @SuppressWarnings("unchecked")
    protected final SELF self() {
        return (SELF) this;
    }

    /**
     * The floor: the guard fails if it examined fewer elements than this. Never below one — a guard that looked at
     * nothing is not green, it is blind.
     */
    public SELF minimumExamined(int minimum) {
        if (minimum < 1) {
            throw new IllegalArgumentException("the floor is at least 1: if there is nothing to examine, do not "
                    + "run the guard");
        }
        this.minimumExamined = minimum;
        return self();
    }

    /** Excuses the finding with this id, for this reason. Fails the guard if it excuses nothing. */
    public SELF exempt(String id, String reason) {
        exemptions.add(id, reason);
        return self();
    }

    /** Runs the guard. */
    public GuardReport check() {
        Collector collector = new Collector();
        evaluate(collector);
        List<Finding> violations = new ArrayList<>();
        List<Finding> inconclusive = new ArrayList<>();
        Map<String, String> exempted = new LinkedHashMap<>();
        for (Finding f : collector.violations) {
            route(f, violations, exempted);
        }
        for (Finding f : collector.inconclusive) {
            route(f, inconclusive, exempted);
        }
        List<String> stale = new ArrayList<>();
        for (String id : exemptions.asMap().keySet()) {
            if (!exempted.containsKey(id)) {
                stale.add(id);
            }
        }
        return new GuardReport(name(), rationale(), remedy(), collector.examined, minimumExamined, violations,
                inconclusive, exempted, stale, collector.notes);
    }

    /** Shortcut for {@code check().assertSatisfied()}. */
    public GuardReport verify() {
        return check().assertSatisfied();
    }

    private void route(Finding f, List<Finding> target, Map<String, String> exempted) {
        if (exemptions.contains(f.id())) {
            exempted.put(f.id(), exemptions.reason(f.id()));
        } else {
            target.add(f);
        }
    }

    /** What a guard reports while it evaluates. */
    protected static final class Collector {

        private int examined;
        private final List<Finding> violations = new ArrayList<>();
        private final List<Finding> inconclusive = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        /** One more element looked at. */
        public void examined() {
            examined++;
        }

        public void examined(int count) {
            examined += count;
        }

        public void violation(String id, String message) {
            violations.add(new Finding(id, message));
        }

        public void inconclusive(String id, String message) {
            inconclusive.add(new Finding(id, message));
        }

        public void note(String note) {
            notes.add(note);
        }
    }
}
