# spring-pg-guardrails

Fail-closed PostgreSQL Row-Level Security for Spring, and the executable guards that prove it holds.

> **Status: 0.1.0-SNAPSHOT.** Not published to Maven Central yet. APIs may change before 1.0.

Multi-tenant applications that share one PostgreSQL database often add Row-Level Security (RLS) as defence in
depth: each row carries a `tenant_id`, a policy compares it with a setting the application writes at the start of
every transaction, and a forgotten `WHERE tenant_id = ?` can no longer leak another tenant's data.

The idea is well documented. Keeping it true is not. The integration test suite connects through Testcontainers
as a **superuser**, and superusers bypass every policy, so the whole suite can be green while:

- a new table has no RLS, or RLS without `FORCE` (the table owner then skips the policies);
- a policy compares with nothing, or its `WITH CHECK` lets a tenant write rows for another tenant;
- an "admin can read everything" escape sits in a `FOR ALL` policy, where it also covers `DELETE`;
- the runtime role gained `BYPASSRLS` to "fix" a query that did not see its rows;
- a transaction sets the tenant on an auto-commit connection, and the setting dies with the statement.

This project gives you a small runtime that makes those mistakes loud, and a test suite that finds them.

## Modules

| Artifact | Scope | What it gives you |
|---|---|---|
| `pg-rls-core` | runtime | `TenantContext` scopes that always restore, transaction-local tenant GUC written at transaction begin, **strict mode** (an error instead of zero rows), read-only admin escape, per-tenant advisory lock, start-up check of the runtime role, SQL templates, Spring Boot 3/4 auto-configuration |
| `pg-rls-test` | test | `RlsCoverage` (catalog), `RlsIsolation` (every RLS table exercised as a non-superuser, rows generated from the catalog), `RuntimeRoleVerifier`, `TenantGucRule` (ArchUnit), `RlsProbe` + JUnit 5 extension |
| `spring-pg-guardrails` | test | The guard engine (floor + exemptions that cannot rot) and eight Spring/PostgreSQL rules born from production incidents |

## Quickstart

### 1. Install the SQL helpers and protect your tables

Copy [`rls-install.sql`](pg-rls-core/src/main/resources/io/github/peppe_git/pgguardrails/rls/sql/rls-install.sql)
into a Flyway migration (it uses Flyway placeholder syntax), then:

```sql
create table invoices (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    total numeric(12, 2) not null
);
-- ENABLE + FORCE row level security, one policy per command; true = allow the read-only admin escape
select rls.enable_tenant_rls('invoices', 'tenant_id', true);
```

```properties
spring.flyway.placeholders.rls_schema=rls
spring.flyway.placeholders.tenant_guc=app.current_tenant
spring.flyway.placeholders.strict_guc=app.rls_strict
spring.flyway.placeholders.admin_read_guc=app.rls_admin_read
```

Connect the application as a role created with
[`runtime-role.sql`](pg-rls-core/src/main/resources/io/github/peppe_git/pgguardrails/rls/sql/runtime-role.sql)
(`NOSUPERUSER NOBYPASSRLS`, owns nothing); run migrations as the owner.

### 2. Bind the tenant; the library writes it into every transaction

```java
// e.g. in a servlet filter, after authentication
TenantContext.callAs(tenantId, () -> { chain.doFilter(request, response); return null; });

@Transactional(readOnly = true)
public List<Invoice> invoices() {
    return invoices.findAll();          // PostgreSQL filters by tenant
}
```

Forgetting the scope is an error (`SQLSTATE RLS01`), not an empty list. Switching tenant inside an open transaction
switches the transaction's setting too, and restores it when the scope closes.

### 3. Prove it in your test suite

```java
@SpringBootTest
class RowLevelSecurityTest {

    @Test
    void everyTenantTableIsCovered() {
        RlsCoverage.of(superuserDataSource)
                .exempt("public.accounts", "read at login, before the tenant is known")
                .verify();
    }

    @Test
    void everyRlsTableIsIsolated() {
        RlsIsolation.of(superuserDataSource).minimumExamined(10).verify();
    }

    @Test
    void theRuntimeRoleIsSubjectToRls() {
        RuntimeRoleVerifier.of(superuserDataSource, "app_runtime").verify();
    }
}
```

[`examples/rls-demo`](examples/rls-demo) is a complete application with every guard wired, plus a broken
migration and the report the guards print about it.

## What you get, and what you do not

**Guarantees** (when the policies come from `enable_tenant_rls` and the application connects as the runtime role):

- a statement on a tenant table sees and changes only the rows of the tenant written in the current transaction;
- no tenant written: zero rows, or with strict mode an error as soon as a policy is evaluated;
- the tenant setting is transaction-local and bound as a parameter: it cannot leak to the next user of a pooled
  connection, and it is never concatenated into SQL;
- the admin read escape widens `SELECT` only, and only in `READ ONLY` transactions;
- the guards fail when a tenant table lacks forced RLS, when a policy would leak, when the runtime role could
  bypass RLS, and when they examined too little to mean anything.

**Non-guarantees** — read [the threat model](docs/threat-model.md):

- **SQL injection is not stopped.** Code that runs arbitrary SQL as the runtime role can write the tenant setting
  itself. RLS protects you from application bugs, not from an attacker who already executes SQL.
- Connections as a superuser, as a table owner with `BYPASSRLS`, views owned by such roles, and `SECURITY DEFINER`
  functions bypass policies. `RuntimeRoleCheck`/`RuntimeRoleVerifier` cover the role; views and functions are on the
  v0.2 list.
- Strict mode raises when a policy is evaluated. A scan of an empty table returns zero rows without error (nothing
  is hidden in that case).
- Transactions opened outside Spring's transaction managers do not get the setting automatically.
- Side channels such as unique-constraint errors ("this e-mail exists") are not covered by RLS at all.
- The guards that read source code (`OnConflictHasArbiter`) are heuristics; they say so when they cannot decide.

## How it compares, and how to use it with other tools

| Tool | Relation |
|---|---|
| Tutorials (AWS Database Blog, bytefish, articles) | The pattern explained; no library, no proof. This project packages the pattern and the proof |
| Small libraries that bind the GUC for JPA | Cover the runtime half for JPA only. Nothing here depends on JPA |
| Hibernate `@TenantId` / filters | Application-level isolation; complementary. Keep both if you like: Hibernate adds the `WHERE`, PostgreSQL enforces it |
| pgTAP | SQL-level tests of policies. Our guards derive the same checks from the catalog inside JUnit and add the role, the code and the isolation exercise |
| [pg-index-health](https://github.com/mfvanek/pg-index-health) | Schema health: foreign keys without an index, duplicated indexes, and more. **Not duplicated here** — run it next to these guards in the same Testcontainers database |
| ArchUnit (`ProxyRules`), vitech arch-tests | Self-invocation of `@Transactional`/`@Async` and other general rules. **Not duplicated here** — our rules use ArchUnit as an engine and accept the same `JavaClasses` |
| forbidden-apis, Error Prone, find-sec-bugs | Locale-less case conversion, SQL built by concatenation. Use them; we do not ship those rules |
| zizmor, pinact, OpenSSF Scorecard | Actions pinned by SHA, images by digest. Use them; we do not ship those rules |

## Documentation

- [Concepts: fail-closed RLS](docs/concepts.md)
- [Threat model](docs/threat-model.md)
- [Configuration reference](docs/configuration.md)
- [Rule catalogue](docs/rules.md) — every guard, why it exists, what it cannot see
- Guides: [adopting in an existing application](docs/guides/adopting.md), [testing RLS](docs/guides/testing-rls.md)
- [Architecture decision records](docs/adr)

## Requirements and building

Java 17+, Spring Framework 6.1+ (Spring Boot 3.2+) or 7 (Spring Boot 4), PostgreSQL 14+. Hibernate 6.6 or 7 for
the two JPA rules. Docker for the tests.

```bash
./mvnw verify            # Spring Boot 4.1
./mvnw verify -Dboot3    # Spring Boot 3.5
scripts/scrub-check.sh   # also run in CI
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
