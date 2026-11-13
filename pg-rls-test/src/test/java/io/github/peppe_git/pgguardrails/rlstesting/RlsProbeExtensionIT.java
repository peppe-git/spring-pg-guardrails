// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rlstesting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.pgguardrails.rls.RlsErrors;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(RlsProbeExtension.class)
class RlsProbeExtensionIT {

    @RlsDataSource
    static DataSource database() {
        return TestDatabase.superuser();
    }

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @BeforeAll
    static void plant() {
        TestDatabase.superuser();
        TestDatabase.execute("""
                create schema if not exists probe;
                create table if not exists probe.items (tenant_id uuid not null, v text);
                select rls.enable_tenant_rls('probe.items', 'tenant_id', true);
                insert into probe.items values ('%s', 'a'), ('%s', 'b');
                """.formatted(A, B));
    }

    @Test
    void theProbeSeesWhatTheTenantMaySee(RlsProbe probe) {
        assertThat(probe.countAsTenant(A, "select count(*) from probe.items where tenant_id in (?, ?)", A, B))
                .isEqualTo(1);
        assertThat(probe.countWithoutTenant("select count(*) from probe.items")).isZero();
        assertThat(probe.countAsAdminReader("select count(*) from probe.items where tenant_id in (?, ?)", A, B))
                .isEqualTo(2);
    }

    @Test
    void theProbeRollsBackWhatItWrites(RlsProbe probe) {
        probe.asTenant(A, s -> s.update("delete from probe.items where tenant_id = ?", A));
        assertThat(probe.countAsTenant(A, "select count(*) from probe.items where tenant_id = ?", A)).isEqualTo(1);
    }

    @Test
    void strictModeSurfacesAsAnSqlState(RlsProbe probe) {
        assertThatThrownBy(() -> probe.withoutTenantStrict(s -> s.count("select count(*) from probe.items")))
                .isInstanceOfSatisfying(RlsProbe.ProbeException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(RlsErrors.STRICT_MODE_SQLSTATE));
    }
}
