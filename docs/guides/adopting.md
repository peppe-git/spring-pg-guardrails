# Adopting in an existing application

The order below never changes behaviour and then breaks it in the same step: first measure, then make loud, then
enforce.

1. **Add the guards first, change nothing.** Add `pg-rls-test` and `spring-pg-guardrails` in `test` scope. Run
   `RlsCoverage` and `RlsIsolation` against the schema your migrations build in Testcontainers. Exempt, with a
   reason, the tables that are outside RLS on purpose; give fixtures to the tables `RlsIsolation` cannot fill.
   Run `RuntimeRoleVerifier` after your real bootstrap script. Fix what they find.
2. **Add the runtime with everything off.** Add `pg-rls-core` with `pg-rls.apply-on-transaction-begin=false` and
   `pg-rls.strict=false`. Replace your own "set the tenant GUC" helper with `TenantGuc.apply(...)` (same
   behaviour, plus the auto-commit check) and your thread-local with `TenantContext` scopes.
3. **Move the policies.** Copy `rls-install.sql` into a new migration, then convert tables with
   `select rls.enable_tenant_rls(...)` and drop the old policies in the same migration. Applied migrations are never
   edited. This is the step that closes the admin-escape `DELETE` if you had a `FOR ALL` policy with the escape in
   `USING`: test the flows that relied on it.
4. **Make it loud.** `pg-rls.strict=true` in staging, then production. Every "empty result" bug becomes an error with
   `SQLSTATE RLS01` and a stack trace.
5. **Let the library write the tenant.** `pg-rls.apply-on-transaction-begin=true`; remove explicit `apply` calls
   where `TenantGucRule` shows the tenant is bound before the transaction starts.
6. **Refuse to start wrong.** `pg-rls.runtime-role-check=fail` in production.
