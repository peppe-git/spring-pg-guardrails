-- SPDX-License-Identifier: Apache-2.0
--
-- One tenant table protected by inline policies, without the helper functions of rls-install.sql.
-- Placeholders: table (unqualified name), tenant_column, tenant_type (e.g. uuid), tenant_guc, admin_read_guc.
--
-- No tenant set => current_setting(..., true) is NULL or '' => NULLIF(...) is NULL => the comparison is never
-- true => zero rows, and every INSERT/UPDATE fails its WITH CHECK. Fail-closed, but silent: strict mode
-- (an error instead of zero rows) needs the helper functions.

ALTER TABLE ${table} ENABLE ROW LEVEL SECURITY;
-- Without FORCE the table owner skips the policy.
ALTER TABLE ${table} FORCE ROW LEVEL SECURITY;

CREATE POLICY ${table}_tenant_isolation ON ${table}
    USING (${tenant_column} = NULLIF(current_setting('${tenant_guc}', true), '')::${tenant_type})
    WITH CHECK (${tenant_column} = NULLIF(current_setting('${tenant_guc}', true), '')::${tenant_type});

-- Optional admin read escape. Keep it in its own FOR SELECT policy. Do NOT write it as
--     USING (tenant = guc OR admin)
-- inside the policy above: that policy also covers DELETE, which has no WITH CHECK, so under the escape a
-- DELETE could remove rows of every tenant. PERMISSIVE policies are OR-ed per command, so this one widens
-- SELECT only. It also requires a READ ONLY transaction.
CREATE POLICY ${table}_admin_read ON ${table} FOR SELECT
    USING (current_setting('${admin_read_guc}', true) = 'on'
           AND current_setting('transaction_read_only') = 'on');
