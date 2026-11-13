// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.autoconfigure;

import io.github.peppe_git.pgguardrails.rls.RlsSettings;
import io.github.peppe_git.pgguardrails.rls.RuntimeRoleCheck;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration of the row-level security runtime, under {@code pg-rls.*}. */
@ConfigurationProperties(prefix = "pg-rls")
public class PgRlsProperties {

    /** Whether the auto-configuration is active. */
    private boolean enabled = true;

    /** GUC holding the current tenant. Must match the name rendered into the SQL functions. */
    private String tenantGuc = RlsSettings.DEFAULT_TENANT_GUC;

    /** GUC that turns "no tenant" into an error (SQLSTATE RLS01) in the library's policies. */
    private String strictGuc = RlsSettings.DEFAULT_STRICT_GUC;

    /** GUC that opens the read-only admin escape. */
    private String adminReadGuc = RlsSettings.DEFAULT_ADMIN_READ_GUC;

    /** Set the strict GUC to 'on' at the start of every Spring-managed transaction. */
    private boolean strict = true;

    /** Write the tenant bound by TenantContext into every Spring-managed transaction as it begins. */
    private boolean applyOnTransactionBegin = true;

    /** Check at start-up that the connecting role is subject to row-level security. */
    private RuntimeRoleCheck.Mode runtimeRoleCheck = RuntimeRoleCheck.Mode.WARN;

    public RlsSettings toSettings() {
        return RlsSettings.builder()
                .tenantGuc(tenantGuc)
                .strictGuc(strictGuc)
                .adminReadGuc(adminReadGuc)
                .strict(strict)
                .applyOnTransactionBegin(applyOnTransactionBegin)
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantGuc() {
        return tenantGuc;
    }

    public void setTenantGuc(String tenantGuc) {
        this.tenantGuc = tenantGuc;
    }

    public String getStrictGuc() {
        return strictGuc;
    }

    public void setStrictGuc(String strictGuc) {
        this.strictGuc = strictGuc;
    }

    public String getAdminReadGuc() {
        return adminReadGuc;
    }

    public void setAdminReadGuc(String adminReadGuc) {
        this.adminReadGuc = adminReadGuc;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public boolean isApplyOnTransactionBegin() {
        return applyOnTransactionBegin;
    }

    public void setApplyOnTransactionBegin(boolean applyOnTransactionBegin) {
        this.applyOnTransactionBegin = applyOnTransactionBegin;
    }

    public RuntimeRoleCheck.Mode getRuntimeRoleCheck() {
        return runtimeRoleCheck;
    }

    public void setRuntimeRoleCheck(RuntimeRoleCheck.Mode runtimeRoleCheck) {
        this.runtimeRoleCheck = runtimeRoleCheck;
    }
}
