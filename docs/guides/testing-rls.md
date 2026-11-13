# Testing row-level security

## The trap

Testcontainers connects as the `postgres` superuser. Superusers bypass every policy, `FORCE`d or not. A test that
inserts rows for tenant A and reads them "as tenant B" through that connection sees them, or does not, for reasons
that have nothing to do with your policies.

## The three layers

1. **Coverage, from the catalog** — `RlsCoverage.of(superuser).verify()`. Fast, no data. Catches forgotten tables,
   missing `FORCE`, policies without the tenant setting, the admin escape outside `FOR SELECT`.
2. **Isolation, by exercising the policies** — `RlsIsolation.of(superuser).verify()`. Generic: every RLS table, rows
   generated from the catalog, run as a non-superuser probe role, everything rolled back.
3. **Your scenarios** — `RlsProbe` for the cases that matter to your domain:

```java
@ExtendWith(RlsProbeExtension.class)
@SpringBootTest
class InvoiceVisibilityTest {

    @RlsDataSource
    static DataSource superuser() {
        return /* a DataSource connecting as the container's superuser */;
    }

    @Test
    void anotherTenantDoesNotSeeTheInvoice(RlsProbe probe) {
        // plant with the (superuser) application DataSource or your repositories, then:
        assertThat(probe.countAsTenant(tenantB, "select count(*) from invoices where tenant_id = ?", tenantA))
                .isZero();
    }
}
```

`RlsProbe` needs a **superuser** `DataSource` (it creates the probe role and switches to it). It takes it from a
static member annotated `@RlsDataSource`, or else from the Spring test context — which only works when the
application itself connects as a superuser. When the application connects as the runtime role (recommended, see
below), declare the superuser one with `@RlsDataSource`. Every call runs in its own transaction, as a temporary
`NOSUPERUSER NOBYPASSRLS` role, and is rolled back.

## Testing the application as it runs in production

Connect the Spring `DataSource` as the runtime role created by your bootstrap script, and Flyway as the owner
(`spring.flyway.user`). Then plain service tests exercise the policies too, and `pg-rls.runtime-role-check=fail`
proves the premise at every context start. `examples/rls-demo` does exactly this.

## Seeing the guards fail

A guard nobody saw fail proves nothing. `examples/rls-demo` keeps a broken migration and a test that asserts what
the guards report about it; `./mvnw -pl examples/rls-demo verify -Ddemo.broken=true` shows the red build.
