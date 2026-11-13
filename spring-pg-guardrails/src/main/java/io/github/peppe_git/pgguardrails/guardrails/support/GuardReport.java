// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one guard run. A guard is satisfied only when it examined at least its minimum number of elements,
 * found no violation and no inconclusive element that is not exempted, and every exemption excused something.
 *
 * @param guard the guard's name
 * @param rationale why the rule exists (printed on failure)
 * @param remedy how to fix a violation (printed on failure)
 * @param examined how many elements the guard looked at
 * @param minimumExamined the floor: below it the guard looked at too little to mean anything
 * @param violations findings not excused by an exemption
 * @param inconclusive elements the guard could not decide, not excused by an exemption
 * @param exempted findings excused, with the reason
 * @param staleExemptions exemptions that excused nothing
 * @param notes informational lines (what was skipped and why)
 */
public record GuardReport(String guard, String rationale, String remedy, int examined, int minimumExamined,
        List<Finding> violations, List<Finding> inconclusive, Map<String, String> exempted,
        List<String> staleExemptions, List<String> notes) {

    public GuardReport {
        violations = List.copyOf(violations);
        inconclusive = List.copyOf(inconclusive);
        exempted = Map.copyOf(exempted);
        staleExemptions = List.copyOf(staleExemptions);
        notes = List.copyOf(notes);
    }

    /** Whether the guard examined enough elements to mean something. */
    public boolean floorReached() {
        return examined >= minimumExamined;
    }

    public boolean isSatisfied() {
        return floorReached() && violations.isEmpty() && inconclusive.isEmpty() && staleExemptions.isEmpty();
    }

    /** Throws an {@link AssertionError} that explains the failure, or returns this report. */
    public GuardReport assertSatisfied() {
        if (!isSatisfied()) {
            throw new AssertionError(describe());
        }
        return this;
    }

    /** A multi-line, human-readable account of the run. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(guard).append(isSatisfied() ? " passed" : " FAILED").append('\n');
        out.append("Examined: ").append(examined).append(" (minimum ").append(minimumExamined).append(")\n");
        if (!floorReached()) {
            out.append("The floor was not reached: the guard looked at fewer elements than it must, so a green ")
                    .append("result would mean nothing. Is the input (packages, sources, schema) the right one?\n");
        }
        section(out, "Violations", violations);
        section(out, "Inconclusive (decide them, or exempt them by id with a reason)", inconclusive);
        if (!staleExemptions.isEmpty()) {
            out.append("Stale exemptions (they excuse nothing any more: remove them): ").append(staleExemptions)
                    .append('\n');
        }
        if (!exempted.isEmpty()) {
            out.append("Exempted: ").append(exempted.size()).append('\n');
        }
        notes.forEach(n -> out.append("Note: ").append(n).append('\n'));
        if (!isSatisfied()) {
            out.append("Why this rule exists: ").append(rationale).append('\n');
            out.append("How to fix: ").append(remedy).append('\n');
        }
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        out.append(title).append(" (").append(findings.size()).append("):\n");
        findings.forEach(f -> out.append("  - ").append(f).append('\n'));
    }

    @Override
    public String toString() {
        return describe();
    }
}
