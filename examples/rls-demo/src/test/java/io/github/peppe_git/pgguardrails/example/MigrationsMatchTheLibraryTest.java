// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.rls.RlsSql;
import org.junit.jupiter.api.Test;

/**
 * For the maintainers of this repository: the example's frozen copy of the helpers is the shipped template. (In
 * your project the copy stays frozen on purpose: a migration never changes, a newer template goes into a new one.)
 */
class MigrationsMatchTheLibraryTest {

    @Test
    void theHelpersMigrationIsTheShippedTemplate() {
        assertThat(DemoDatabase.resource("/db/migration/V1__rls_helpers.sql")).isEqualTo(RlsSql.read(RlsSql.INSTALL));
    }
}
