# 7. A floor and two-way exemptions in every guard

Status: accepted

## Context

Two failure modes recurred in the original suite. A guard fed with nothing (renamed package, pruned build output,
empty schema) loops over nothing and is green. An exemption list outlives the code it excused, and a new element
with the same name inherits the absolution. The original fix was a separate guard parsing test sources for lists
with suggestive names, which only checked that the cited file still existed.

## Decision

A small engine (`Guard`, `GuardReport`, `Exemptions`) used by every rule: each rule counts what it examined and fails
below a floor (default 1, never 0); each exemption needs a reason and must match a current finding, or the guard
fails; inconclusive elements fail unless exempted. Failures print the reason the rule exists and the remedy.

## Consequences

- Stale exemptions are caught even when the element still exists but stopped violating the rule.
- No dependency on AssertJ or JUnit in the engine (`AssertionError`).
- A rule that legitimately has nothing to examine in a project should not be run there.
