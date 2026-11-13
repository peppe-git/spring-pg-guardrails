# 6. Guards read the catalog, not the migration files

Status: accepted

## Context

The original guards derived "which tables have RLS" and "which unique keys exist" by applying regular expressions to
Flyway migrations, replaying `CREATE`/`ALTER`/`DROP` in version order. That misses `DO` blocks, functions such as
`enable_tenant_rls()`, dynamic SQL, and anything not written in the expected shape.

## Decision

Guards that ask about the schema run against the database built by the migrations in Testcontainers and read
`pg_class`, `pg_policy`, `pg_index`, `pg_constraint`, `pg_roles`. Only the SQL written in application code
(`ON CONFLICT`) is still read from source text.

## Consequences

- The answer is what PostgreSQL enforces, whatever produced it.
- The guards need a database; `MigrationChecksumLedger` and the bytecode rules do not.
