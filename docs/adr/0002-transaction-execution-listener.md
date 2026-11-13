# 2. The setting is written by a TransactionExecutionListener

Status: accepted

## Context

Writing the tenant automatically at transaction begin can be done by subclassing
`DataSourceTransactionManager.prepareTransactionalConnection`, by a custom `JpaDialect.beginTransaction`, by an
aspect around `@Transactional`, or by Spring 6.1's `TransactionExecutionListener.afterBegin`.

## Decision

`RlsTransactionListener implements TransactionExecutionListener`, registered on existing transaction managers by a
`BeanPostProcessor` (or by hand with `addListener`). It writes through `JdbcTemplate`, which picks up the
transaction's connection for both JDBC and JPA managers.

## Consequences

- No subclass to replace: user-defined and Boot-defined transaction managers keep working.
- One code path for `DataSourceTransactionManager`, `JdbcTransactionManager` and `JpaTransactionManager`
  (verified by tests on both). Aspects would have to fight ordering with the transaction interceptor.
- Requires Spring Framework 6.1 (Spring Boot 3.2+).
- Transactions not run by a Spring `AbstractPlatformTransactionManager` are not covered; strict mode is the net.
- With JPA the connection is acquired at transaction begin instead of lazily.
