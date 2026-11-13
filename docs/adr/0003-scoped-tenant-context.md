# 3. TenantContext has scopes, not set/clear

Status: accepted

## Context

A thread-local tenant with public `set` and `clear` relies on every caller restoring it in a `finally`. Threads are
pooled; a missed restore leaves the previous tenant on the thread for the next request or task. The original code
base had a guard scanning sources for `set(` without a `finally`, which is a textual heuristic.

## Decision

No public `set`. Only `callAs`/`runAs` scopes that restore the previous value, `wrap` for hand-over to other
threads, and `TenantContextTaskDecorator`. A scope opened inside a tracked transaction also switches and restores the
transaction's GUC; inside an untracked transaction a switch is refused.

## Consequences

- The leak cannot be written, so no guard is needed for it.
- Servlet filters use `callAs(tenant, () -> { chain.doFilter(...); return null; })`; checked exceptions pass through
  unchanged thanks to the generic exception parameter.
- Code that switched tenant in the middle of an untracked transaction now fails loudly.
