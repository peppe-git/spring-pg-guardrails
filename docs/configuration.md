# Configuration reference

## Spring Boot properties (`pg-rls-core`)

| Property | Default | Meaning |
|---|---|---|
| `pg-rls.enabled` | `true` | Turns the auto-configuration off entirely |
| `pg-rls.tenant-guc` | `app.current_tenant` | GUC holding the tenant. Must match the name rendered into the SQL helpers |
| `pg-rls.strict-guc` | `app.rls_strict` | GUC that turns a missing tenant into `SQLSTATE RLS01` |
| `pg-rls.admin-read-guc` | `app.rls_admin_read` | GUC that opens the read-only admin escape |
| `pg-rls.strict` | `true` | Write `strict-guc = on` at the start of every Spring transaction |
| `pg-rls.apply-on-transaction-begin` | `true` | Write the tenant bound by `TenantContext` at the start of every Spring transaction |
| `pg-rls.runtime-role-check` | `warn` | At start-up, check the connecting role is subject to RLS: `off`, `warn`, `fail` (recommended in production) |

GUC names must look like `prefix.name` (lower case, one dot), as PostgreSQL requires for custom settings.

## Beans

The auto-configuration (active when there is a single `DataSource`) contributes:

- `TenantGuc` — writes the settings of the current transaction;
- `TenantAdvisoryLock` — `pg_advisory_xact_lock` per `(namespace, tenant)`;
- a `BeanPostProcessor` that registers `RlsTransactionListener` on every `ConfigurableTransactionManager` whose
  `DataSource` is that one (`DataSourceTransactionManager`, `JdbcTransactionManager`, `JpaTransactionManager`);
- the start-up runtime role check.

With several `DataSource`s, create the beans yourself and register the listener:

```java
TenantGuc guc = new TenantGuc(tenantDataSource, RlsSettings.defaults());
transactionManager.addListener(new RlsTransactionListener(guc));
```

Without Spring Boot, the same two lines are the whole wiring.

## Thread pools

`TenantContextTaskDecorator` carries the submitting thread's tenant into tasks and restores the worker thread
afterwards:

```java
executor.setTaskDecorator(new TenantContextTaskDecorator());
```

## SQL templates (`RlsSql`)

| Template | Placeholders | Use |
|---|---|---|
| `rls-install.sql` | `rls_schema`, `tenant_guc`, `strict_guc`, `admin_read_guc` | Helper functions and `enable_tenant_rls(regclass, name, boolean)`; copy into a migration |
| `runtime-role.sql` | `runtime_role`, `rls_schema` | The runtime role; run by your database bootstrap (needs `CREATEROLE`) |
| `rls-policy-inline-template.sql` | `table`, `tenant_column`, `tenant_type`, `tenant_guc`, `admin_read_guc` | One table with hand-written policies, if you do not want the functions (no strict mode) |

The placeholder syntax is Flyway's (`${name}`): copy a template into a migration and set
`spring.flyway.placeholders.<name>`, or render it with `RlsSql.install(settings, "rls")` /
`RlsSql.render(template, values)` and paste the result. Treat the copy like any migration: it never changes; a newer
template goes into a new migration.

`rls.enable_tenant_rls(target regclass, tenant_column name default 'tenant_id', admin_read boolean default false)`
enables and forces RLS on the table and on every existing partition, and (re)creates the policies
`rls_tenant_select`, `rls_tenant_insert`, `rls_tenant_update`, `rls_tenant_delete`. Other policies on the table are
left in place: they are permissive and would be OR-ed with these, so drop legacy policies in the same migration.

## Test-side configuration

Every guard extends the same base:

| Method | Meaning |
|---|---|
| `minimumExamined(n)` | Fail if fewer than `n` elements were examined (default 1, never 0) |
| `exempt(id, reason)` | Excuse the finding with that id; fails the guard if it excuses nothing |
| `check()` | Run and return a `GuardReport` |
| `verify()` | `check().assertSatisfied()` — throws an `AssertionError` with the reason and the remedy |

Guard-specific options are listed in the [rule catalogue](rules.md).

## Spring Boot 3 and 4

One jar works with both: the auto-configuration orders itself after the `DataSource` auto-configuration of either
version by class name. The build tests Spring Boot 4.1 by default and 3.5 with `-Dboot3`.
