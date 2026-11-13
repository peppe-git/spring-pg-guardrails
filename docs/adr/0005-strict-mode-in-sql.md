# 5. Strict mode is implemented in the policy functions

Status: accepted

## Context

Fail-closed RLS is silent: a forgotten tenant yields zero rows. The Java side cannot know which tables a transaction
will touch, so it cannot decide that a missing tenant is an error.

## Decision

The policy functions raise `SQLSTATE RLS01` when no tenant is set and the strict GUC is `on`. The listener sets the
strict GUC at every transaction (`pg-rls.strict=true` by default); it can also be a role default.

## Consequences

- The error appears where the mistake is, with a stack trace, in tests and in production.
- Only policies that call the functions are strict; inline policies stay silent.
- Security quals are evaluated per row: a statement over an empty table returns zero rows without error. PostgreSQL
  does not hoist a pseudo-constant security qual into a one-time filter (verified), so this cannot be fixed in SQL.
- The functions are called through `(SELECT ...)` so that PostgreSQL evaluates them once per statement.
