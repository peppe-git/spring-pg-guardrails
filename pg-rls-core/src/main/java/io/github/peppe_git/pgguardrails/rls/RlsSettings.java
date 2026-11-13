// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Names of the PostgreSQL settings (GUCs) the policies read, and the runtime switches. Immutable.
 *
 * <p>The names must match the ones rendered into the SQL install script ({@link RlsSql}): the database reads
 * them, the application writes them.
 */
public final class RlsSettings {

    /** Default name of the GUC holding the current tenant. */
    public static final String DEFAULT_TENANT_GUC = "app.current_tenant";

    /** Default name of the GUC that turns "no tenant" from zero rows into an error. */
    public static final String DEFAULT_STRICT_GUC = "app.rls_strict";

    /** Default name of the GUC that opens the read-only admin escape. */
    public static final String DEFAULT_ADMIN_READ_GUC = "app.rls_admin_read";

    /** Custom GUCs must have a prefix: {@code prefix.name}, lower case. */
    private static final Pattern GUC_NAME = Pattern.compile("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");

    private final String tenantGuc;
    private final String strictGuc;
    private final String adminReadGuc;
    private final boolean strict;
    private final boolean applyOnTransactionBegin;

    private RlsSettings(Builder b) {
        this.tenantGuc = validName(b.tenantGuc, "tenantGuc");
        this.strictGuc = validName(b.strictGuc, "strictGuc");
        this.adminReadGuc = validName(b.adminReadGuc, "adminReadGuc");
        if (tenantGuc.equals(strictGuc) || tenantGuc.equals(adminReadGuc) || strictGuc.equals(adminReadGuc)) {
            throw new IllegalArgumentException("the three GUC names must be distinct: " + tenantGuc + ", "
                    + strictGuc + ", " + adminReadGuc);
        }
        this.strict = b.strict;
        this.applyOnTransactionBegin = b.applyOnTransactionBegin;
    }

    private static String validName(String name, String what) {
        Objects.requireNonNull(name, what);
        if (!GUC_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " must look like 'prefix.name' (lower case): " + name);
        }
        return name;
    }

    /** The defaults: {@code app.current_tenant}, strict mode on, GUC applied at transaction begin. */
    public static RlsSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String tenantGuc() {
        return tenantGuc;
    }

    public String strictGuc() {
        return strictGuc;
    }

    public String adminReadGuc() {
        return adminReadGuc;
    }

    /** Whether every Spring-managed transaction sets {@link #strictGuc()} to {@code on}. */
    public boolean strict() {
        return strict;
    }

    /** Whether every Spring-managed transaction receives the tenant bound by {@link TenantContext}. */
    public boolean applyOnTransactionBegin() {
        return applyOnTransactionBegin;
    }

    @Override
    public String toString() {
        return "RlsSettings[tenantGuc=" + tenantGuc + ", strictGuc=" + strictGuc + ", adminReadGuc=" + adminReadGuc
                + ", strict=" + strict + ", applyOnTransactionBegin=" + applyOnTransactionBegin + "]";
    }

    /** Builder for {@link RlsSettings}. */
    public static final class Builder {

        private String tenantGuc = DEFAULT_TENANT_GUC;
        private String strictGuc = DEFAULT_STRICT_GUC;
        private String adminReadGuc = DEFAULT_ADMIN_READ_GUC;
        private boolean strict = true;
        private boolean applyOnTransactionBegin = true;

        private Builder() {
        }

        public Builder tenantGuc(String name) {
            this.tenantGuc = name;
            return this;
        }

        public Builder strictGuc(String name) {
            this.strictGuc = name;
            return this;
        }

        public Builder adminReadGuc(String name) {
            this.adminReadGuc = name;
            return this;
        }

        public Builder strict(boolean strict) {
            this.strict = strict;
            return this;
        }

        public Builder applyOnTransactionBegin(boolean apply) {
            this.applyOnTransactionBegin = apply;
            return this;
        }

        public RlsSettings build() {
            return new RlsSettings(this);
        }
    }
}
