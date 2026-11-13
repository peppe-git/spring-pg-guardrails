// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.autoconfigure;

import io.github.peppe_git.pgguardrails.rls.RuntimeRoleCheck;
import java.util.List;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Runs {@link RuntimeRoleCheck} once all singletons exist, and warns or refuses to start. */
final class RuntimeRoleStartupCheck implements SmartInitializingSingleton {

    private static final Log log = LogFactory.getLog(RuntimeRoleStartupCheck.class);

    private final DataSource dataSource;
    private final RuntimeRoleCheck.Mode mode;

    RuntimeRoleStartupCheck(DataSource dataSource, RuntimeRoleCheck.Mode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (mode == RuntimeRoleCheck.Mode.OFF) {
            return;
        }
        RuntimeRoleCheck.Report report = RuntimeRoleCheck.inspect(dataSource);
        List<String> problems = report.problems();
        if (problems.isEmpty()) {
            log.debug("pg-rls: role '" + report.role() + "' is subject to row-level security");
            return;
        }
        String message = "pg-rls: the application connects as a role that is NOT subject to row-level security, "
                + "so no policy protects anything: " + String.join("; ", problems)
                + ". Connect as a NOSUPERUSER NOBYPASSRLS role that owns nothing (see runtime-role.sql).";
        if (mode == RuntimeRoleCheck.Mode.FAIL) {
            throw new IllegalStateException(message + " (pg-rls.runtime-role-check=fail)");
        }
        log.warn(message + " Set pg-rls.runtime-role-check=fail in production.");
    }
}
