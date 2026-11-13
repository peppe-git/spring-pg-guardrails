# 1. The tenant lives in a transaction-local GUC, written with a bound parameter

Status: accepted

## Context

The policies need the current tenant. Options: a session-level setting (`set_config(..., false)` or `SET`), one
database role per tenant, or a transaction-local setting (`set_config(..., true)`).

## Decision

Transaction-local, written with `select set_config(?, ?, true)` and the value bound as a parameter.

## Consequences

- No leak through connection pools or poolers in transaction mode: the value dies at commit or rollback.
- Every write needs an open transaction and a connection not in auto-commit; otherwise the value dies with the
  statement and the following queries see no tenant. `TenantGuc` checks both and throws.
- Code outside a transaction cannot read tenant tables at all (fail-closed), which is the intended behaviour.
- One extra round trip per transaction (strict flag and tenant are written in one statement).
