# Rule catalogue

Every guard here was written after a defect that the normal test suite could not see. Each entry says why it
exists, what it looks at, what it cannot see, and the id to use in `exempt(id, reason)`.

## Two principles shared by every guard

**A floor.** A guard reports how many elements it examined and fails below its minimum (default 1, never 0). A
rule that imports a misspelt package, reads an empty directory or a schema without tables finds no violation —
because it looked at nothing. Without a floor that is a green build.

**Exemptions by name that cannot rot.** An exemption names the element it excuses and gives a reason. It must keep
excusing a current finding: when the element is fixed or deleted, the stale exemption fails the guard. Otherwise
the list grows into names nobody re-reads, and the day a new element takes one of those names it inherits an
absolution nobody reviewed.

Inconclusive elements — the guard could not decide — fail too, unless exempted.

---

## Row-level security (`pg-rls-test`)

### `RlsCoverage`

*Why.* A tenant table added without RLS fails no test and raises no error; its rows are simply readable by every
tenant. `ENABLE` without `FORCE` looks protected and is not for the table owner. A table with RLS and no policy
works in superuser tests and denies everything in production.

*Looks at* the catalog of the given schemas: tables and partitions with the tenant column must have RLS enabled and
forced; no table may have RLS without `FORCE` or without a policy; every permissive policy on a tenant table must use
the tenant setting (`USING` for the commands it covers, the effective `WITH CHECK` for writes); the admin escape may
appear only in `FOR SELECT` policies.

*Cannot see* whether the expression is *correct* — only that it uses the setting. That is `RlsIsolation`'s job.

*Ids:* `schema.table`, `schema.table#no-policy`, `schema.table#policy`.
Options: `schemas(...)`, `tenantColumn(...)`, `settings(...)`, `tenantMarker(...)`, `adminMarker(...)`.

### `RlsIsolation`

*Why.* Policies that exist, are enabled and forced can still leak. Only exercising them as a role subject to RLS
shows it, and Testcontainers connects as a superuser.

*Looks at* every RLS table with the tenant column. Plants a row for tenant A (generated from the catalog; foreign
key triggers off), then as a non-superuser probe role: A sees it (positive control); B sees no other tenant's row,
updates none, deletes none, cannot insert a row for A; with no tenant nothing is visible or deletable; when a
policy mentions the admin escape, a read-only transaction reads A's row and a read-write one cannot delete, update
or insert it; leaf partitions queried directly hide A's row. All in one transaction, rolled back.

*Cannot see* tables it cannot fill (complex `CHECK`, triggers that need context): those are inconclusive until you
add `fixture("schema.table", "insert ... values (:tenant, :unique, ...)")` or exempt them.

*Needs* a superuser connection. *Ids:* `schema.table`.

### `RuntimeRoleVerifier`

*Why.* Every policy rests on a premise kept in a bootstrap script no test reads: the runtime role is not a superuser,
has no `BYPASSRLS`, cannot `SET ROLE` to a role that has either, owns no non-forced table. Adding `BYPASSRLS` is the
natural shortcut in front of a query that "does not see its rows".

*Looks at* the named roles in the catalog, after you ran your real bootstrap script in the test database. Also flags
`CREATEROLE`. The runtime twin is `RuntimeRoleCheck` (`pg-rls.runtime-role-check`). *Ids:* the role name.

### `TenantGucRule` (ArchUnit)

*Why.* With explicit GUC writes, every transactional path that touches a tenant table must write the tenant first.

*Looks at* methods that open a transaction (`@Transactional`, or `TransactionTemplate.execute*`) and, within the same
class, reach a Spring Data repository whose entity maps an RLS table; they must call an applier
(`TenantGuc.apply/applyFromContext/runAs`, `TenantContext.callAs/runAs`, or yours via `applier(...)`).

*Cannot see* `JdbcTemplate`/native SQL access and paths through other beans. Not useful with
`apply-on-transaction-begin=true` (rely on strict mode). *Ids:* `com.acme.Service#method`.

---

## Spring code (`spring-pg-guardrails`, bytecode and context)

### `NoRemoteCallInTransaction`

*Why.* A transaction held across a network call or a sub-process keeps a pooled connection (and its locks) for the
whole call. Invisible in development; under load the pool drains and every request waits.

*Looks at* methods whose body runs in a transaction because of `@Transactional` (method, class, or a composed
annotation; not `SUPPORTS`/`NOT_SUPPORTED`/`NEVER`) and the calls reachable inside the same class. Remote calls by
default: `HttpClient.send/sendAsync`, `ProcessBuilder.start`, `Runtime.exec`, `URL.openConnection/openStream`,
`Socket.connect`, `RestTemplate`/`RestOperations`, `RestClient` and `WebClient` request specs. Add yours with
`remoteCall(type, methods...)`.

*Cannot see* calls made by other beans, and transactions opened with `TransactionTemplate` (sequence and nesting are
indistinguishable in a call graph; a short read followed by a call is correct). *Ids:* `com.acme.Service#method`.

### `InjectedClockConsistency`

*Why.* A class with an injected `Clock` that also reads the system time keeps time-travel tests green while part of
its logic ignores them.

*Looks at* classes with a `java.time.Clock` field: no `X.now()` without a clock, `System.currentTimeMillis()`,
`new Date()`, or `Clock.system*()` outside constructors. *Ids:* the class name.

### `UnambiguousComponentConstructors`

*Why.* With several constructors and none `@Autowired`, Spring looks for the no-argument one: the context fails to
start (usually in production, where the conditional bean is finally active), or starts without injecting anything.

*Looks at* classes annotated or meta-annotated `@Component` (so `@Service`, `@Configuration`…) with more than one
non-synthetic constructor and none annotated `@Autowired`/`@Inject`. *Ids:* the class name.

### `AsyncExecutorDeclared`

*Why.* `@Async` without `@EnableAsync` is inert (the method runs on the caller's thread). With `@EnableAsync`, as soon
as the application declares its own `Executor` beans Spring Boot's executor backs off, and Spring may fall back to
`SimpleAsyncTaskExecutor`: one new thread per call, no bound, no error.

*Looks at* a running `ApplicationContext`, and asks Spring itself (`AsyncExecutionAspectSupport`) which default
executor it would use; `AsyncConfigurer` overriding `getAsyncExecutor()` counts as a choice; `@Async("name")` must
name an existing `Executor`. *Ids:* `async-not-enabled`, `default-executor`, `qualifier:<name>`.

## Code and schema together

### `OnConflictHasArbiter`

*Why.* `ON CONFLICT (a, b)` needs a unique index on exactly those columns, or PostgreSQL rejects the whole statement
— only when that branch finally runs, and idempotency branches are the rarest.

*Looks at* SQL in source files (`.java`, `.kt`, `.sql`; Java string concatenation joined, comments ignored) and the
catalog's unique indexes and constraints. A partial unique index counts only if the target repeats a `WHERE`.

*Cannot see* SQL built dynamically (inconclusive). *Ids:* `path#table(target)`.

### `JpaNullabilityMatchesSchema`

*Why.* `hbm2ddl.auto=validate` does not compare nullability. An entity that promises non-null over a nullable column
reads `null` one day; a primitive field over a nullable column fails the query.

*Looks at* Hibernate's mapping model (so naming strategies and embeddables are resolved as Hibernate does) and the
catalog. Skips `SINGLE_TABLE` subclass columns. Hibernate 6.6 or 7. *Ids:* `table.column`.

### `EnumValuesMatchCheckConstraints`

*Why.* The allowed values live twice, in the enum and in a `CHECK`. A constant added without its migration fails on
its first write in production; a value the enum lacks cannot be read back. Mocks cannot see either.

*Looks at* enum attributes bound as strings or as PostgreSQL enums, against the column's enum labels or its
set-like `CHECK` (`IN (...)`, `= ANY (ARRAY[...])`), in both directions. Skips ordinals, `AttributeConverter`s and
`@EnumeratedValue`. `requireConstraint()` also flags unconstrained enum columns. *Ids:* `table.column`.

### `MigrationChecksumLedger`

*Why.* Flyway refuses to start when an applied migration changed by one byte — even a comment — and production
usually has no `repair`. CI migrates an empty database and never notices.

*Looks at* a ledger file (`<migration> <sha-256>` per line) versus the files on disk; line endings and BOM are
normalised. Not git history: shallow CI checkouts would answer "no change" to everything. Bootstrap the file once
with `render()`. *Ids:* the file name.

---

## Planned for v0.2

`RawSqlTenantGuc` (native SQL paths), `AdminReadWriteScope` (no writes in the unit that opens the escape), views and
`SECURITY DEFINER` functions that bypass RLS, `EqualityPredicateIndexCoverage`, `PoolFitsMaxConnections`,
`EveryYamlKeyHasAReader`, `EnvExampleCoverage`, `CacheableReturnsNoEnum`, Spring Data JDBC entities in
`TenantGucRule`, `ArchRule` adapters.

## Deliberately not here

| Rule | Use instead |
|---|---|
| Every foreign key has an index | pg-index-health |
| Self-invocation of `@Transactional`/`@Async` | ArchUnit `ProxyRules`, vitech arch-tests |
| Case conversion without `Locale` | forbidden-apis |
| SQL identifiers concatenated into queries | find-sec-bugs / SpotBugs |
| GitHub Actions pinned by SHA, images by digest | zizmor, pinact, OpenSSF Scorecard |
