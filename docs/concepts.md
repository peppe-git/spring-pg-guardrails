# Concepts: fail-closed row-level security

## The model

One database, one schema, many tenants. Every tenant table has a tenant column (default `tenant_id`). PostgreSQL
row-level security (RLS) attaches **policies** to the table: boolean expressions evaluated for every row a statement
reads (`USING`) or writes (`WITH CHECK`). The policies compare the tenant column with a **custom setting** (a GUC,
default `app.current_tenant`) that the application writes at the start of each transaction:

```sql
select set_config('app.current_tenant', '<tenant id>', true);   -- true = local to the transaction
```

## Fail-closed

When the setting is missing, `current_setting('app.current_tenant', true)` returns `NULL` (or `''` once it has been
set in the session). The comparison `tenant_id = NULL` is never true, so the statement sees **zero rows** and every
insert fails its `WITH CHECK`. A forgotten tenant cannot leak data: that is what *fail-closed* means.

It is also why fail-closed is dangerous in a different way: the bug is silent. A service that forgot the tenant
returns an empty list, a background job "finds nothing to do", a lookup concludes "not found". Hence:

## Strict mode

The library's policies do not compare with the setting directly; they call `rls.tenant_for_read()` and
`rls.tenant_for_write()`. These functions return the tenant, or `NULL` when it is missing — unless the strict
setting (`app.rls_strict`) is `on`, in which case they raise `SQLSTATE RLS01`. The application turns strict mode
on for every Spring transaction (`pg-rls.strict=true`, the default); you can also make it the default of the
runtime role (`ALTER ROLE app_runtime SET app.rls_strict = 'on'`).

Limit: the error is raised when a policy is evaluated, i.e. when the scan meets a row (or an index lookup needs the
value). A statement over an empty table returns zero rows without error — there is nothing to hide in that case.

## Transaction-local, always

`set_config(..., true)` scopes the value to the current transaction. It disappears at commit or rollback, so a
pooled connection can never carry one tenant's setting into the next borrower's work. The trap of transaction-local
values is the auto-commit connection: there, "the transaction" is the `set_config` statement itself, and the value is
gone before the next query. `TenantGuc` refuses to write on an auto-commit connection or outside a Spring
transaction (`RlsUsageException`).

The setting is always passed as a **bound parameter**, never concatenated.

## Who writes the setting

`RlsTransactionListener` (a Spring `TransactionExecutionListener`) runs right after a new transaction begins and
writes the strict flag and the tenant bound by `TenantContext`. It works with `DataSourceTransactionManager`,
`JdbcTransactionManager` and `JpaTransactionManager`; the Boot auto-configuration registers it on the transaction
manager of the primary `DataSource`. Nested (savepoint) and participating transactions keep the outer setting.

`TenantContext` has no public `set`: only scopes (`callAs`, `runAs`) that restore the previous tenant in a
`finally`. Opening a scope inside a transaction the library tracks also switches that transaction's setting, and
restores it when the scope closes. Inside a transaction the library does not track, switching is refused — the
thread would say one tenant and the database another.

## FORCE, owners and superusers

- Without `FORCE ROW LEVEL SECURITY`, the **table owner** skips the policies. Migrations usually run as the owner;
  the application must not, but `FORCE` makes it irrelevant.
- **Superusers** and roles with **`BYPASSRLS`** skip every policy, forced or not. The application must connect as a
  role that is neither, owns nothing, and is not a member of a role that is (it could `SET ROLE`).
- Testcontainers connects as a superuser: integration tests prove nothing about RLS unless they switch to such a
  role (`SET LOCAL ROLE`), which is what `RlsIsolation` and `RlsProbe` do.

## One policy per command, and the DELETE trap

PostgreSQL combines **permissive** policies with `OR`, per command. A single `FOR ALL` policy covers `SELECT`,
`INSERT`, `UPDATE` and `DELETE`; its `WITH CHECK` guards only new rows. A common way to give administrators a
cross-tenant view is:

```sql
create policy p on t
  using (tenant_id = <tenant> or current_setting('app.platform_admin', true) = 'on')
  with check (tenant_id = <tenant>);
```

`DELETE` has no `WITH CHECK`: its rows are chosen by `USING` alone. Under the escape, this policy lets a `DELETE`
remove rows of **every** tenant. (`UPDATE` is stopped by the `WITH CHECK` only if the new row fails it.) And if the
`WITH CHECK` is omitted, PostgreSQL applies `USING` to new rows too, so the escape also writes.

`enable_tenant_rls()` writes four policies — `FOR SELECT`, `FOR INSERT`, `FOR UPDATE`, `FOR DELETE` — and the admin
escape exists only in the `SELECT` one. The escape function also requires a `READ ONLY` transaction, and
`TenantGuc.applyAdminRead()` refuses to run in a read-write one. Writing as an administrator means writing as the
tenant that owns the row: read with the escape, then write in a transaction for that tenant.

## Partitions

A query through a partitioned table applies the parent's policies; a query that names a **partition** directly
applies the partition's own. `enable_tenant_rls()` protects the parent and every partition that exists when it
runs; partitions created later need the call too, and `RlsCoverage` checks every partition.

## Two questions, two perimeters

"On which tables does the database enforce isolation?" (the RLS tables) and "which tables hold a tenant's rows?"
(every table with the tenant column, including identity tables deliberately outside RLS) have different answers.
The guards use the first for isolation and the second for coverage, and an exemption makes the difference explicit
and documented. Code that walks *all* of a tenant's data — deletion, export — must use the second.
