# 4. One policy per command; the admin escape is read-only by construction

Status: accepted

## Context

A single `FOR ALL` policy with `USING (tenant = guc OR admin)` and `WITH CHECK (tenant = guc)` was meant to give
administrators read-only access. `DELETE` has no `WITH CHECK`, so its rows are selected by `USING` alone: under the
escape, a `DELETE` removes rows of every tenant. Measured on PostgreSQL 17 in `SqlPoliciesIT`.

## Decision

`enable_tenant_rls()` creates four policies (`FOR SELECT`, `FOR INSERT`, `FOR UPDATE`, `FOR DELETE`). Reads compare
with `tenant_for_read()`, writes with `tenant_for_write()`; only the `SELECT` policy may OR the admin escape, whose
function also requires a `READ ONLY` transaction. `TenantGuc.applyAdminRead()` refuses read-write transactions.
`RlsCoverage` rejects the escape in any other policy; `RlsIsolation` tries the cross-tenant `DELETE`.

## Consequences

- An administrator writes as the tenant that owns the row: read with the escape, then write per tenant.
- Four policies per table instead of one; generated, so no hand-written duplication.
- The escape is still a GUC any SQL running as the runtime role can set (see the threat model); a separate admin
  role is the stronger option.
