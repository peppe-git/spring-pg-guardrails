# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `pg-rls-core`: `TenantContext` scopes, `TenantGuc` (transaction-local, bound parameter, auto-commit check,
  `runAs` with restore, read-only admin escape), `RlsTransactionListener`, strict mode (`SQLSTATE RLS01`),
  `TenantAdvisoryLock`, `RuntimeRoleCheck`, `TenantContextTaskDecorator`, SQL templates (`rls-install.sql` with
  `enable_tenant_rls`, `runtime-role.sql`, inline policy template) and Spring Boot 3/4 auto-configuration.
- `pg-rls-test`: `RlsCoverage`, `RlsIsolation` (generic, every RLS table), `RuntimeRoleVerifier`, `TenantGucRule`,
  `RlsProbe` and `RlsProbeExtension`.
- `spring-pg-guardrails`: guard engine (floor, two-way exemptions) and the rules `NoRemoteCallInTransaction`,
  `InjectedClockConsistency`, `UnambiguousComponentConstructors`, `AsyncExecutorDeclared`, `OnConflictHasArbiter`,
  `JpaNullabilityMatchesSchema`, `EnumValuesMatchCheckConstraints`, `MigrationChecksumLedger`.
- `examples/rls-demo`: a Testcontainers application with every guard, and a broken migration the guards catch.
- Release tooling: Maven Central metadata, a `release` profile (sources, javadoc, signatures, Central Portal upload
  with manual publication), a release workflow triggered by `v*` tags, Dependabot, and `RELEASING.md`.
